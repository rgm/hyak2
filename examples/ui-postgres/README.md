# Web admin example

Show how to use jnunemaker/flipper infrastructure to manage Redis flippers.

## Usage

1. Make sure you have Ruby + bundler installed.
2. Set DATABASE_URL and HYAK_TABLE_PREFIX in env, see `./start`
3. Issue `./start` at the shell to stand up a Rack-based web UI.
4. Go to `http://127.0.0.1:9292` to see the web UI.
