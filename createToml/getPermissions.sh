#!/bin/bash
set -euxo pipefail
wget -N https://raw.githubusercontent.com/Xeyame/essinfo.xeya.me/master/data/commands.json
wget -N https://raw.githubusercontent.com/Xeyame/essinfo.xeya.me/master/data/permissions.json
./getPermissions.py > commandPermissions.toml
