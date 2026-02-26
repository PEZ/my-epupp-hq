(ns hq.hello-world)

;; This is a Rich Comment form.
;; you can load this file without the content of the form being evaluated.
;; Evaluate expressions inside the comment form one by one
(comment
  (js/alert "[Epupp HQ] Hello World!")

  ;; Open Developer Tools in the browser, then evaluate
  (js/console.log "[Epupp HQ] Hello World!")
  :rcf)