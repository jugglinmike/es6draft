# -*- mode: ruby -*-
# vi: set ft=ruby :

Vagrant.configure(2) do |config|
  config.vm.box = "ubuntu/trusty64"

  config.vm.synced_folder "../test262-v8-machinery/test262", "/test262"

  config.vm.provision "shell", inline: <<-SHELL
    #!/bin/bash -e
    sudo add-apt-repository ppa:webupd8team/java
    sudo apt-get update
    # Automated installation
    echo oracle-java8-installer shared/accepted-oracle-license-v1-1 select true | sudo /usr/bin/debconf-set-selections
    sudo apt-get install -y oracle-java8-installer
    sudo apt-get install -y maven

    cd /vagrant
    mvn package

    wget https://nodejs.org/dist/v5.3.0/node-v5.3.0-linux-x64.tar.gz
    tar -xvf node-v5.3.0-linux-x64.tar.gz
    mkdir /opt/joyent
    mv node-v5.3.0-linux-x64 /opt/joyent/node-5.3.0
    chown vagrant /opt/joyent/node-5.3.0
    ln -s /opt/joyent/node-5.3.0 /opt/joyent/node
    echo 'PATH=$PATH:/opt/joyent/node/bin' >> /home/vagrant/.bashrc
  SHELL
  # mvn test -P test262 -Dtest262.path=/test262 -Dtest262.include="test/built-ins/Proxy/**/*.js"
end
