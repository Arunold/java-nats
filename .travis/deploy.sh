#!/bin/sh

echo "executing deploy.sh"

if [ ! -z "$TRAVIS_TAG" ]
then
    echo "on a tag -> set pom.xml <version> to $TRAVIS_TAG"
    mvn --settings .travis/settings.xml versions:set -DnewVersion=$TRAVIS_TAG 1>/dev/null 2>/dev/null
else
    echo "not on a tag -> keep snapshot version in pom.xml"
fi

# Temporarily disabled due to credential issues.
# echo "decrypting keyrings"
# openssl aes-256-cbc -pass pass:$ENCRYPTION_PASSWORD -in $GPG_DIR/pubring.gpg.enc -out $GPG_DIR/pubring.gpg -d
# openssl aes-256-cbc -pass pass:$ENCRYPTION_PASSWORD -in $GPG_DIR/secring.gpg.enc -out $GPG_DIR/secring.gpg -d
# mvn deploy --settings .travis/settings.xml -Pnats-release -Dskip.unit.tests=true -B

${TRAVIS_BUILD_DIR}/.travis/publish-javadoc.sh
