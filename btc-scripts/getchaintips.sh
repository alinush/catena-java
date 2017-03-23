#!/bin/bash
set -e

scriptdir=$(cd $(dirname $0); pwd -P)
cli=$scriptdir/cli.sh

set -x
$cli getchaintips
