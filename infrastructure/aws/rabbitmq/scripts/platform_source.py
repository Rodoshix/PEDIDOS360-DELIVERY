"""Load #69's versioned inventory, without maintaining a second topology."""
import importlib.util
import os
from pathlib import Path
import sys

configured = os.environ.get('EP2_PLATFORM_SOURCE')
source = (Path(configured) if configured else
          Path(__file__).resolve().parents[3] / 'rabbitmq/platform_control.py').resolve()
module = sys.modules.get('platform_control')
if module is not None and Path(module.__file__).resolve() != source:
    raise ImportError('A different platform_control source is already loaded')
if module is None:
    spec = importlib.util.spec_from_file_location('platform_control', source)
    module = importlib.util.module_from_spec(spec)
    sys.modules['platform_control'] = module
    spec.loader.exec_module(module)
# Adapter assignments (Api) must affect the globals used by #69 functions.
sys.modules[__name__] = module
