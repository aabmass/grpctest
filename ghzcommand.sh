#!/bin/bash
ghz --insecure --call grpctest.HelloService.SayHello -d '{"message": "Hello from GHZ Load Test"}' localhost:50051 -n 100000 -c 10
