#!/usr/bin/env bash

# set motd
cp /rstudio/vagrant/build.motd.tail /etc/motd

# add repo for R 
su -c 'rpm -Uvh http://download.fedoraproject.org/pub/epel/6/i386/epel-release-6-8.noarch.rpm'

# install R
yum update
yum install -y R

# install minimal packages needed to run bootstrap scripts
yum install -y git

# create SSH key if needed
if [ ! -f /rstudio/vagrant/vagrant_key_rsa ]; then
    ssh-keygen -t rsa -b 4096 -N "" -C "vagrant ssh" -f /rstudio/vagrant/vagrant_key_rsa
    chmod 0600 /rstudio/vagrant/vagrant_key_rsa.pub
fi

