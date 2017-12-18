# akka failure detector benchmark

This project runs a benchmark for failure detectors (FD) against an akka cluster.
Get more of the background [in this article](https://manuel.bernhardt.io/2017/07/26/a-new-adaptive-accrual-failure-detector-for-akka/).

## Configuration

Configure the benchmark by creating the file `conf/application-prod.conf` (it is included in the main `conf/application.conf`).
 
You'll need to configure [Mailgun](https://www.mailgun.com/) credentials and a benchmark plan, like so:

    reporting.email.to = "<reporting email>"
    reporting.mailgun.domain = "<mailgun domain>"
    reporting.mailgun.key = "<mailgun key>"

    benchmark.plan = [
      { fd: "akka.remote.AdaptiveAccrualFailureDetector", threshold: 0.2 },
      { fd: "akka.remote.AdaptiveAccrualFailureDetector", threshold: 0.25 },
      { fd: "akka.remote.AdaptiveAccrualFailureDetector", threshold: 0.3 },
      { fd: "akka.remote.PhiAccrualFailureDetector", threshold: 8 },
      { fd: "akka.remote.PhiAccrualFailureDetector", threshold: 9 },
      { fd: "akka.remote.PhiAccrualFailureDetector", threshold: 10 }
    ]

## Deployment

First, build the benchmark JAR:

    sbt assembly
    
Next, checkout the [Terraform](https://terraform.io) project at [https://github.com/manuelbernhardt/akka-cluster-provision](https://github.com/manuelbernhardt/akka-cluster-provision).

You will need to manually copy the resulting JAR file from the previous step into the terraform project folder, it is expected to be named `akka-fd-benchmark.jar`.

Follow the installation instructions of the terraform project (you will need to create a consul cluster) and then run the terraform job, which will also result in running the benchmark.

Do not forget to remove the infrastructure after the benchmark has run using `terraform destroy`.



