#!/usr/bin/env bash
# Build SGDC and run it on the airport dataset.
set -e
cd "$(dirname "$0")"

if [ ! -f data/airport.csv ]; then
  mkdir -p data
  unzip -q -o experiment/datasets.zip -d data
fi

if [ ! -f target/SGDC.jar ]; then
  mvn -q -DskipTests clean package
fi

java -jar target/SGDC.jar --dataset=data/airport.csv --sgdc.seed=1 --sgdc.dump=out.tsv "$@"
echo "DCs written to out.tsv"
