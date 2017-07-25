#!/bin/bash

export TF_VAR_servers=3
export TF_VAR_members=3
export TF_VAR_consul_server_address=172.31.82.39

git pull
sbt assembly
cp target/scala-2.12/akka-fd-benchmark.jar ../akka-cluster-provision
cd ../akka-cluster-provision
git pull

terraform destroy -force -var 'provider.aws.region=us-east-1' -var 'region=us-east-1' -var 'key_name=akka' -var 'key_path=/home/ubuntu/.ssh/akka.pem' -var 'aws_security_group=sg-25880654' -var 'papertrail_host=logs6.papertrailapp.com' -var 'papertrail_port=30269'

terraform apply -var 'provider.aws.region=us-east-1' -var 'region=us-east-1' -var 'key_name=akka' -var 'key_path=/home/ubuntu/.ssh/akka.pem' -var 'aws_security_group=sg-25880654' -var 'papertrail_host=logs6.papertrailapp.com' -var 'papertrail_port=30269'
