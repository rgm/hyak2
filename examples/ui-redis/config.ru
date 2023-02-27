# frozen_string_literal: true

# https://www.flippercloud.io/docs/ui

require 'flipper-ui'
require 'flipper/adapters/redis'

options = {}

options[:url] = ENV['REDIS_URL']
options[:password] = ENV['REDIS_PASSWORD'] if ENV['REDIS_PASSWORD']
# use below when eg. heroku gives self-signed rediss://
# options[:ssl_params] = { verify_mode: OpenSSL::SSL::VERIFY_NONE }

client = Redis.new options
adapter = Flipper::Adapters::Redis.new client
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
