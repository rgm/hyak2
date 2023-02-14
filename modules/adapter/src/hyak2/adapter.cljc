(ns hyak2.adapter
  "Specs and a protocol for implementing a hyak feature store.")

(defprotocol IFStore
  "Reader methods for implementing a feature store (fstore)."

  (-features [fstore]
    "Get a seq of all known features in the store. Each feature is a map with
     :fkey plus metadata.")

  (-expired? [fstore fkey]
    "Is the feature consided out-of-date (scan these to help with cleanup).")

  (-author [fstore fkey]
    "Who introduced this feature?")

  (-enabled? [fstore fkey akey]
    "Is the feature currently enabled for this actor?")

  (-add! [fstore fkey expires-at author]
    "Add a feature key to the store. (This is idempotent).

     Provide an expires-at inst for when to start warning that this feature is
     old and should be cleaned up (eg. in CI, or in logs). Provide an author
     (eg. an email adddress) so others can know who to ask about a feature.")

  (-remove! [fstore fkey]
    "Remove a feature from the store.")

  (-disable! [fstore fkey]
    "Close all gates on a feature.")

  (-enable! [fstore fkey]
    "Open boolean gate on a feature.")

  (-enable-actor! [fstore fkey akey]
    "Open actor gate on a feature.")

  (-disable-actor! [fstore fkey akey]
    "Close actor gate on a feature.")

  (-groups [fstore]
    "List all registered groups.")

  (-register-group! [fstore gkey pred]
    "Register a group predicate.")

  (-unregister-group! [fstore gkey]
    "Unregister a group predicate.")

  (-enable-group! [fstore fkey gkey]
    "Open group gate on a feature for a group key.")

  (-disable-group! [fstore fkey gkey]
    "Close group gate on a feature for a group key."))
