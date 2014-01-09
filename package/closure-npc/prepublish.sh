#!/bin/sh

set -e -x;

cd ../../;
ant closure-npc;
mkdir -p package/closure-npc/lib;
cp build/closure-npc.jar package/closure-npc/lib/;
