;; Generate comparisons between two sets of variant calls
;; Utilizes GATK walkers to generate detailed and summary statistics
;; about two sets of calls
;; - Identify non-callable regions with CallableLociWalker
;; - Combine variants from two samples
;; - Use VariantEval to calculate overall concordance statistics
;; - Provide output for concordant and discordant regions for
;;   detailed investigation

(ns bcbio.variation.compare
  (:import [org.broadinstitute.sting.gatk CommandLineGATK])
  (:use [bcbio.variation.variantcontext :only [parse-vcf write-vcf-w-template]]
        [bcbio.variation.stats :only [vcf-stats write-summary-table]]
        [bcbio.variation.report :only [concordance-report-metrics
                                       write-concordance-metrics]]
        [clojure.math.combinatorics :only [combinations]]
        [clojure.java.io])
  (:require [fs.core :as fs]
            [clj-yaml.core :as yaml]))

;; Utility functions for processing file names

(defn file-root [fname]
  "Retrieve file name without extension: /path/to/fname.txt -> /path/to/fname"
  (let [i (.lastIndexOf fname ".")]
    (if (pos? i)
      (subs fname 0 i)
      fname)))

(defn add-file-part [fname part]
  "Add file extender: base.txt -> base-part.txt"
  (format "%s-%s%s" (file-root fname) part (fs/extension fname)))

(defn- run-gatk [program args]
  (let [std-args ["-T" program "--phone_home" "NO_ET"]]
    (CommandLineGATK/start (CommandLineGATK.)
                           (into-array (concat std-args args)))))

;; GATK walker based variance assessment

(defn combine-variants [vcf1 vcf2 ref]
  "Combine two variant files with GATK CombineVariants."
  (letfn [(unique-name [f]
            (-> f fs/base-name file-root))]
    (let [out-file (add-file-part vcf1 "combine")
          args ["-R" ref
                (str "--variant:" (unique-name vcf1)) vcf1
                (str "--variant:" (unique-name vcf2)) vcf2
                "-o" out-file
                "--genotypemergeoption" "UNIQUIFY"]]
      (if-not (fs/exists? out-file)
        (run-gatk "CombineVariants" args))
      out-file)))

(defn variant-comparison [sample vcf1 vcf2 ref & {:keys [out-base]
                                                  :or {out-base nil}}]
  "Compare two variant files with GenotypeConcordance in VariantEval"
  (let [out-file (str (file-root (if (nil? out-base) vcf1 out-base)) ".eval")
        args ["-R" ref
              "--out" out-file
              "--eval" vcf1
              "--comp" vcf2
              "--sample" sample
              "--evalModule" "GenotypeConcordance"
              "--stratificationModule" "Sample"]]
    (if-not (fs/exists? out-file)
      (run-gatk "VariantEval" args))
    out-file))

(defn select-by-concordance [sample call1 call2 ref & {:keys [out-dir]
                                                       :or {out-dir nil}}]
  "Variant comparison producing 3 files: concordant and both directions discordant"
  (let [base-dir (if (nil? out-dir) (fs/parent (:file call1)) out-dir)]
    (if-not (fs/exists? base-dir)
      (fs/mkdirs base-dir))
    (doall
     (for [[c1 c2 cmp-type] [[call1 call2 "concordance"]
                             [call1 call2 "discordance"]
                             [call2 call1 "discordance"]]]
       (let [out-file (str (fs/file base-dir (format "%s-%s-%s-%s.vcf"
                                                     sample (:name c1) (:name c2) cmp-type)))
             args ["-R" ref
                   "--sample_name" sample
                   "--variant" (:file c1)
                   (str "--" cmp-type) (:file c2)
                   "--out" out-file]]
         (if-not (fs/exists? out-file)
           (run-gatk "SelectVariants" args))
         out-file)))))

;; Custom parsing and combinations using GATK VariantContexts

(defn- vc-by-match-category [in-file]
  "Lazy stream of VariantContexts categorized by concordant/discordant matching."
  (letfn [(genotype-alleles [g]
            (vec (map #(.toString %) (:alleles g))))
          (is-concordant? [vc]
            (= (-> (map genotype-alleles (:genotypes vc))
                   set
                   count)
               1))]
    (for [vc (parse-vcf in-file)]
      [(if (is-concordant? vc) :concordant :discordant)
       vc])))

(defn split-variants-by-match [vcf1 vcf2 ref]
  "Provide concordant and discordant variants for two variant files."
  (let [combo-file (combine-variants vcf1 vcf2 ref)
        out-map {:concordant (add-file-part combo-file "concordant")
                 :discordant (add-file-part combo-file "discordant")}]
    (if-not (fs/exists? (:concordant out-map))
      (write-vcf-w-template combo-file out-map (vc-by-match-category combo-file)
                            ref))
    out-map))

(defn- get-summary-writer [config config-file]
  (if-not (nil? (:outdir config))
    (do
      (if-not (fs/exists? (:outdir config))
        (fs/mkdirs (:outdir config)))
      (writer (str (fs/file (:outdir config)
                            (format "%s-summary.txt"
                                    (file-root (fs/base-name config-file)))))))
    (writer System/out)))

(defn -main [config-file]
  (let [config (-> config-file slurp yaml/parse-string)]
    (with-open [w (get-summary-writer config config-file)]
      (doseq [exp (:experiments config)]
        (.write w (format "* %s\n" (:sample exp)))
        (doseq [[c1 c2] (combinations (:calls exp) 2)]
          (.write w (format "** %s and %s\n" (:name c1) (:name c2)))
          (let [c-files (select-by-concordance (:sample exp) c1 c2 (:ref exp)
                                               :out-dir (:outdir config))
                eval-file (variant-comparison (:sample exp) (:file c1) (:file c2)
                                              (:ref exp) :out-base (first c-files))
                metrics (first (concordance-report-metrics (:sample exp) eval-file))]
            (doseq [f c-files]
              (.write w (format "%s\n" (fs/base-name f)))
              (write-summary-table (vcf-stats f) :wrtr w))
            (write-concordance-metrics metrics w)))))))
