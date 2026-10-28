"""What Beacon uses from Hermes, read from the plugin's own source, so a Hermes release that renames one is named instead of silently degrading a feature.

shortcut: module-level names only (modules, functions, classes, constants and module attributes); a renamed method,
keyword or return shape on a Hermes object is not seen here. The plugin tests that call those paths are the check for them.
"""
import ast
import importlib.util
import sys
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parent
_IMPORT_ERRORS = {'ImportError', 'ModuleNotFoundError'}
# Third-party packages the plugin works without (they are only imported inside a fallback).
OPTIONAL_PACKAGES = {'firebase_admin'}


def _caught(handler):
    kinds = handler.type.elts if isinstance(handler.type, ast.Tuple) else [handler.type] if handler.type else []
    return {k.id if isinstance(k, ast.Name) else getattr(k, 'attr', '') for k in kinds}


def _scan(body, aliases, path, found, optional=False):
    """Adds requirement groups to found: tuples of (module, name, attribute, file, optional); a group holds when any entry does."""
    scope = dict(aliases)
    nested = []

    def need(entries):
        found.append(tuple(entries))

    def visit(node, group, optional):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.Lambda)):
            nested.append((node, optional))
            return
        if isinstance(node, ast.Try):
            # An import in a handler is an alternative: either one serves. Catching only ImportError marks an optional import.
            alternatives = any(isinstance(n, (ast.Import, ast.ImportFrom)) for h in node.handlers for n in ast.walk(h))
            guarded = bool(node.handlers) and all(_caught(h) and _caught(h) <= _IMPORT_ERRORS for h in node.handlers)
            local = [] if alternatives else group
            for child in node.body + [c for h in node.handlers for c in h.body]:
                visit(child, local, optional or (guarded and not alternatives))
            for child in node.orelse + node.finalbody:
                visit(child, group, optional)
            if alternatives and local:
                need(local)
            return
        if isinstance(node, (ast.Import, ast.ImportFrom)):
            if isinstance(node, ast.ImportFrom):
                if node.level or not node.module:
                    return  # the plugin's own modules
                bound = [((node.module, a.name), a.asname or a.name) for a in node.names if a.name != '*']
            else:
                bound = [((a.name, None), a.asname) for a in node.names]
                for a in node.names:
                    if not a.asname:
                        scope[a.name.split('.')[0]] = (a.name.split('.')[0], None, optional)
            for (module, name), alias in bound:
                if module.startswith('beacon_'):
                    continue  # siblings loaded under a beacon_ key
                entry = (module, name, None, path, optional)
                group.append(entry) if group is not None else need([entry])
                if alias:
                    scope[alias] = (module, name, optional)
            return
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id in ('getattr', 'hasattr') \
                and len(node.args) >= 2 and isinstance(node.args[0], ast.Name) and isinstance(node.args[1], ast.Constant):
            target = scope.get(node.args[0].id)
            # getattr with a default and hasattr are the plugin's own fallbacks; plain getattr is a need.
            if target and node.func.id == 'getattr' and len(node.args) == 2 and not node.keywords:
                need([(target[0], target[1], node.args[1].value, path, target[2])])
            for child in node.args[2:] + [k.value for k in node.keywords]:
                visit(child, group, optional)
            return
        if isinstance(node, ast.Attribute) and isinstance(node.value, ast.Name) and isinstance(node.ctx, ast.Load):
            target = scope.get(node.value.id)
            if target:
                need([(target[0], target[1], node.attr, path, target[2])])
                return
        for child in ast.iter_child_nodes(node):
            visit(child, group, optional)

    for statement in body:
        visit(statement, None, optional)
    for function, guarded in nested:
        inner = dict(scope)
        arguments = function.args
        for argument in arguments.posonlyargs + arguments.args + arguments.kwonlyargs + [arguments.vararg, arguments.kwarg]:
            if argument is not None:
                inner.pop(argument.arg, None)  # a parameter shadows a module alias
        _scan([function.body] if isinstance(function, ast.Lambda) else function.body, inner, path, found, guarded)


def needs(root=ROOT):
    found = []
    for source in sorted(root.rglob('*.py')):
        if 'tests' not in source.relative_to(root).parts:
            _scan(ast.parse(source.read_text(encoding='utf-8-sig')).body, {}, source.relative_to(root).as_posix(), found)
    return found


class Source:
    """Hermes' modules as source text: which names each defines, without importing or starting Hermes."""

    def __init__(self, hermes_root):
        self.root = Path(hermes_root).resolve()
        self.tops = {p.stem if p.is_file() else p.name for p in self.root.iterdir()
                     if (p.is_file() and p.suffix == '.py') or (p.is_dir() and any(p.glob('*.py')))}
        self.defined = lru_cache(maxsize=None)(self._defined)

    def file(self, module):
        base = self.root.joinpath(*module.split('.'))
        for candidate in (base.with_suffix('.py'), base / '__init__.py'):
            if candidate.is_file():
                return candidate
        return base if base.is_dir() else None

    def _defined(self, module, _seen=frozenset()):
        path = self.file(module)
        if path is None or path.is_dir() or module in _seen:
            return frozenset()
        names = set()
        tree = ast.parse(path.read_text(encoding='utf-8-sig'))
        for node in self._top(tree.body):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                names.add(node.name)
            elif isinstance(node, (ast.Assign, ast.AnnAssign, ast.AugAssign)):
                for target in node.targets if isinstance(node, ast.Assign) else [node.target]:
                    names.update(n.id for n in ast.walk(target) if isinstance(n, ast.Name))
            elif isinstance(node, ast.Import):
                names.update(a.asname or a.name.split('.')[0] for a in node.names)
            elif isinstance(node, ast.ImportFrom):
                for a in node.names:
                    if a.name == '*':
                        source = self._absolute(module, node, path)
                        if source and source.split('.')[0] in self.tops and source != module:
                            names |= self._defined(source, _seen | {module})
                    else:
                        names.add(a.asname or a.name)
        if '__getattr__' in names:
            names.add('*')  # a module-level __getattr__ answers any name
        if module == 'tui_gateway.server':
            # Hermes' split method modules publish their own definitions onto server (method_ctx.bind_module).
            for split in self.root.joinpath('tui_gateway').glob('*.py'):
                text = split.read_text(encoding='utf-8-sig')
                if 'bind_module(globals()' in text:
                    for node in self._top(ast.parse(text).body):
                        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                            names.add(node.name)
                        elif isinstance(node, (ast.Assign, ast.AnnAssign)):
                            for target in node.targets if isinstance(node, ast.Assign) else [node.target]:
                                names.update(n.id for n in ast.walk(target) if isinstance(n, ast.Name))
        return frozenset(names)

    @staticmethod
    def _top(body):
        for node in body:
            yield node
            for field in ('body', 'orelse', 'finalbody', 'handlers'):
                children = getattr(node, field, None)
                if isinstance(children, list) and not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                    yield from Source._top(c for child in children for c in (child.body if isinstance(child, ast.ExceptHandler) else [child]))

    def _absolute(self, module, node, path):
        if not node.level:
            return node.module
        package = module.split('.') if path.name == '__init__.py' else module.split('.')[:-1]
        package = package[:len(package) - node.level + 1]
        return '.'.join(package + ([node.module] if node.module else []))

    def has(self, module, name):
        return name in self.defined(module) or '*' in self.defined(module) or self.file(module + '.' + name) is not None

    def holds(self, entry):
        module, name, attribute, _, optional = entry
        top = module.split('.')[0]
        if top in sys.stdlib_module_names:
            return True
        if top not in self.tops:
            if top in OPTIONAL_PACKAGES or optional:
                return True
            # Not Hermes' and not installed means a package Hermes renamed or dropped; another installed package is its own contract.
            try:
                return importlib.util.find_spec(top) is not None
            except (ImportError, ValueError):
                return False
        if self.file(module) is None:
            return optional
        if name is not None and not self.has(module, name):
            return optional
        if attribute is None:
            return True
        target = module if name is None else module + '.' + name if self.file(module + '.' + name) else None
        # An attribute of a non-module object (a class, an instance) is that object's own contract.
        return target is None or self.has(target, attribute) or optional


def missing(groups, hermes_root):
    """Each requirement this Hermes source cannot satisfy, as 'module.name.attribute (file)'."""
    source = Source(hermes_root)
    result = set()
    for group in groups:
        if not any(source.holds(entry) for entry in group):
            result.add(' or '.join('.'.join(p for p in e[:3] if p) + f' ({e[3]})' for e in group))
    return sorted(result)
