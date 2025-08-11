#!/bin/sh
mkdir -p out
javac -d out src/MainServer.java
echo "Run: java -cp out glowfolio.MainServer"
