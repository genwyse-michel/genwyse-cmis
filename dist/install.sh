#!/bin/sh
mvn install:install-file \
  -DgroupId=com.genwyse.cmis \
  -DartifactId=genwyse-cmis \
  -Dpackaging=jar \
  -Dversion=1.1.0 \
  -Dfile=genwyse-cmis-1.1.0.jar \
  -DgeneratePom=true
