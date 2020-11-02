#!/bin/bash

if [ -z $1 ]; then
    echo "usage: $0 <version>"
    exit 1
fi

VERSION=$1
GIT_HASH=$(git rev-parse HEAD)

set -eux

git tag $VERSION
git push origin $VERSION

mkdir -p target

cp pom-template.xml pom.xml
clojure -Srepro -Spom
sed -e "s/VERSION/$VERSION/g" \
    -e "s/GIT_HASH/$GIT_HASH/g" \
    -i '' \
    pom.xml
mv pom.xml target/pom.xml

clojure -Srepro \
        -A:pack \
        mach.pack.alpha.skinny \
        --no-libs \
        --project-path target/timbre-json-appender.jar
mvn deploy:deploy-file \
    -Dfile=target/timbre-json-appender.jar \
    -DrepositoryId=clojars \
    -Durl=https://clojars.org/repo \
    -DpomFile=target/pom.xml
