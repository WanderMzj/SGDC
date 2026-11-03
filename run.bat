@echo off
rem Build SGDC and run it on the airport dataset.
cd /d "%~dp0"

if not exist data\airport.csv (
  mkdir data 2>nul
  powershell -NoProfile -Command "Expand-Archive -Force experiment\datasets.zip data"
)

if not exist target\SGDC.jar (
  call mvn -q -DskipTests clean package
)

java -jar target\SGDC.jar --dataset=data\airport.csv --sgdc.seed=1 --sgdc.dump=out.tsv %*
echo DCs written to out.tsv
