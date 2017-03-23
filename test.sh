#!/bin/bash

# NOTE: Can run test method 'testXyz' in class TestCircle using:
#
#  $ mvn -Dtest=TestCircle#testXyz test
#
# Or, can run all tests in TestCircle using
#
#  $ mvn -Dtest=TestCircle
#

# https://stackoverflow.com/questions/1873995/run-a-single-test-method-with-maven
# "Use -DfailIfNoTests=false to skip projects without test. No Tests Were Executed happens when you try to run test from root project and there is modules without tests at all."

scriptdir=$(cd $(dirname $0); pwd -P)
. $scriptdir/set-env.sh

export MAVEN_OPTS="-ea"

mvn -DfailIfNoTests=false $@ test
