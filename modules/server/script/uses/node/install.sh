#!/bin/bash
if [ $NODE_VERSION ]; then
  echo "NODE_VERSION ${NODE_VERSION}"
else
  echo "not found NODE_VERSION"
  exit 1
fi
cd /tmp
wget https://registry.npmmirror.com/-/binary/node/v${NODE_VERSION}/node-v${NODE_VERSION}-linux-x64.tar.gz
tar -zxf node-v${NODE_VERSION}-linux-x64.tar.gz
cp -r node-v${NODE_VERSION}-linux-x64/* /opt/node/
