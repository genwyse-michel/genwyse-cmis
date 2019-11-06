#!/bin/sh
version=$1
file=genwyse-cmis
if [ "$version" == "" ]; then
  version=`/bin/ls $file-*.jar| sed "s/^$file-//"|sed 's/\.jar$//'|tail -1`
fi
echo "Installation de la version $version"

mvn install:install-file \
  -DgroupId=com.genwyse.cmis \
  -DartifactId=genwyse-cmis \
  -Dpackaging=jar \
  -Dversion=$version \
  -Dfile=genwyse-cmis-$version.jar \
  -DgeneratePom=true
