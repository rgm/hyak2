# frozen_string_literal: true

# https://www.flippercloud.io/docs/ui

require 'sequel'

# configure Sequel before requiring flipper-sequel
DB = Sequel.connect ENV['DATABASE_URL']
Sequel::Model.db = DB

require 'flipper-ui'
require 'flipper-sequel'

# tell sequel about actual table names
prefix = ENV['HYAK_TABLE_PREFIX']
feature_table = "#{prefix}flipper_features".to_sym
gate_table = "#{prefix}flipper_gates".to_sym
class Feature < Sequel::Model(feature_table); end
class Gate < Sequel::Model(gate_table); end

adapter = Flipper::Adapters::Sequel.new(feature_class: Feature, gate_class: Gate)
fstore = Flipper.new(adapter)

Flipper::UI.configure do |config|
  config.descriptions_source = lambda { |_keys|
    { 'my:feature' => 'Lorem ipsum dolor sit amet, consetetur sadipscing elitr, sed diam nonumy eirmod tempor invidunt ut labore et dolore magna aliquyam erat, sed diam voluptua. At vero eos et accusam et justo duo dolores et ea rebum. Stet clita kasd gubergren, no sea takimata sanctus est Lorem ipsum dolor sit amet.' }
  }

  config.show_feature_description_in_list = true
  config.banner_text = ENV['FLIPPER_BANNER']
  # #{primary secondary success danger warning info light dark}
  config.banner_class = ENV['FLIPPER_COLOR']
  config.fun = true
end

run Flipper::UI.app(fstore) { |builder|
  secret = ENV.fetch('SESSION_SECRET') { SecureRandom.hex(20) }
  builder.use(Rack::Session::Cookie, secret:)
  builder.use Rack::Auth::Basic do |_username, password|
    password == 'secret'
  end
}

# vt:ft=ruby
