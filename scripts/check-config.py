"""配置门禁：application.yaml 的环境变量 ↔ .env.example，app.* 配置项 ↔ IDE 元数据。

用法：python3 scripts/check-config.py
退出码非 0 表示存在未登记/失配的键，CI 与提交前本地检查都以此为准。
"""
import json
import os
import re
import sys

# 控制台编码非 UTF-8 时（如 Windows GBK），打印含汉字的 FAIL 行会 UnicodeEncodeError 崩掉门禁
for _stream in (sys.stdout, sys.stderr):
    if hasattr(_stream, "reconfigure"):
        _stream.reconfigure(encoding="utf-8", errors="replace")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
YAML = os.path.join(ROOT, "backend", "src", "main", "resources", "application.yaml")
ENV = os.path.join(ROOT, ".env.example")
META = os.path.join(ROOT, "backend", "src", "main", "resources", "META-INF",
                    "additional-spring-configuration-metadata.json")

yaml_text = open(YAML, encoding='utf-8').read()
env_text = open(ENV, encoding='utf-8').read()

# yaml 里的 ${VAR}（含嵌套默认值 ${A:${B:8080}}，用前看断言避免漏掉外层名）
yaml_vars = set(re.findall(r'\$\{([A-Z][A-Z0-9_]*)(?=[:}])', yaml_text))
env_keys = set(re.findall(r'^([A-Z][A-Z0-9_]*)=', env_text, re.M))
# 注释掉的键＝"已登记但默认不设"（如 MANAGEMENT_SERVER_ADDRESS：设了反而在同端口时启动失败）
env_commented = set(re.findall(r'^#\s*([A-Z][A-Z0-9_]*)=', env_text, re.M))

print(f"yaml vars: {len(yaml_vars)}   env keys: {len(env_keys)} + {len(env_commented)} 注释键")
only_yaml = sorted(yaml_vars - (env_keys | env_commented))
only_env = sorted(env_keys - yaml_vars)
for v in only_yaml:
    print(f"  FAIL yaml 引用但 .env.example 缺失: {v}")
for v in only_env:
    print(f"  FAIL .env.example 有但 yaml 未引用: {v}")

# app.* 叶键 vs 元数据：按缩进解析 app: 块
leaves = set()
lines = yaml_text.split('\n')
start = next(i for i, l in enumerate(lines) if re.match(r'^app:\s*$', l))
stack = []
for l in lines[start + 1:]:
    if l.strip() and not l.lstrip().startswith('#') and re.match(r'^\S', l):
        break
    m = re.match(r'^(\s+)([A-Za-z0-9_.-]+):', l)
    if not m or not l.strip():
        continue
    ind = len(m.group(1))
    stack = [(si, sn) for si, sn in stack if si < ind]
    stack.append((ind, m.group(2)))
    leaves.add('app.' + '.'.join(sn for _, sn in stack))
# 只保留叶键：去掉是其它键前缀的
leaf_keys = {k for k in leaves if not any(o != k and o.startswith(k + '.') for o in leaves)}

meta = json.load(open(META, encoding='utf-8'))
meta_app = {p['name'] for p in meta.get('properties', []) if p['name'].startswith('app.')}

print(f"app.* leaves: {len(leaf_keys)}   metadata app.*: {len(meta_app)}")
for k in sorted(leaf_keys - meta_app):
    print(f"  FAIL 代码有配置项但元数据缺失: {k}")
for k in sorted(meta_app - leaf_keys):
    print(f"  FAIL 元数据有但 yaml 无此项: {k}")

bad = len(only_yaml) + len(only_env) + len(leaf_keys - meta_app) + len(meta_app - leaf_keys)
print("CONFIG GATES:", "OK" if bad == 0 else f"{bad} FAILURES")
sys.exit(1 if bad else 0)
