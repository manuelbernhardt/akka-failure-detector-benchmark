#!/bin/bash
sbt run -Dakka.remote.netty.tcp.port=2552 -Dbenchmark.expected-members=3 &
sbt run -Dakka.remote.netty.tcp.port=2553 -Dbenchmark.expected-members=3 &
sbt run -Dakka.remote.netty.tcp.port=2554 -Dbenchmark.expected-members=3 &


