(ns hyak.adapter
  "Specs and a protocol for implementing a hyak feature store.")

(defprotocol IFStore
  (-features [fstore]
    "List all known features in the store.")

  (-add! [fstore fkey]
    [fstore fkey expires-at]
    [fstore fkey expires-at author]
    "Add a feature key to the store. (This is idempotent).

     Provide an expires-at inst for when to start warning that this feature is
     old and should be cleaned up (eg. in CI, or in logs).")

  (-remove! [fstore fkey]
    "Remove a feature from the store.")

  (-disable! [fstore fkey]
    "Close all gates on a feature."))
