#!/usr/bin/env python3

import json

with open("commands.json") as f:
    commands = json.load(f)["data"]

with open("permissions.json") as f:
    permissions = json.load(f)["data"]

ALIASES = {}
for pl, cmd, rawAliases, desc, helpText in commands:
    if not rawAliases:
        continue
    ALIASES[cmd] = [i.strip() for i in rawAliases.split(",")]

commands = {}

for pl, cmd, node, desc in permissions:
    if not cmd or not node:
        continue

    if cmd not in commands and cmd in ALIASES:
        commands[cmd] = node

    if cmd in commands and len(commands[cmd]) > len(node):
        commands[cmd] = node

for cmd in sorted(set(commands)):
    node = commands[cmd]

    for c in sorted({cmd, *ALIASES[cmd]}):
        print(f'{c} = "{node}"')
