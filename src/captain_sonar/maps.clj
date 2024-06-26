(ns captain-sonar.maps)

(def alpha #{[2 2] [3 2] [7 2] [12 2] [2 3] [9 3] [12 3] [9 4] [5 6] [15 6]
             [3 7] [12 7] [2 8] [7 8] [8 8] [9 8] [12 8] [4 9] [13 9] [5 10]
             [2 11] [8 11] [11 11] [2 12] [4 12] [14 12] [4 13] [7 13] [4 14]
             [8 14] [14 14]})

(comment
  ;; prints the map to stdout
  (mapv (fn [y]
          (mapv (fn [x]
                  (if (contains? alpha [x y])
                      (print \X)
                      (print \·)))
               (range 1 16))
          (println ""))
       (range 1 16)))
