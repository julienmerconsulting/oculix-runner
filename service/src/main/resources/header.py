# -*- coding: utf-8 -*-
# Injected by oculix-runner-service in front of every script. Do not edit here:
# the service owns these lines, your script starts after the marker below.
from sikuli import *
import time
import os
import sys
import json

# RUN: what the service knows about this run (id, params, target).
try:
    with open(r"__RUN_JSON__") as _f:
        RUN = json.load(_f)
except Exception:
    RUN = {}
PARAMS = RUN.get("params", {})
TARGET = RUN.get("target", {})

# The VNC target comes from the base, not from the container environment.
# The password is resolved here, from the environment variable named by
# secret_ref, and never written anywhere by the service.
if TARGET.get("host"):
    os.environ["TARGET_VNC_HOST"] = str(TARGET["host"])
    os.environ["TARGET_VNC_PORT"] = str(TARGET.get("port") or 5900)
if TARGET.get("secret_ref"):
    TARGET["password"] = os.environ.get(TARGET["secret_ref"], "")


def step(label, status="PASS", detail=""):
    """Declare a step. Statuses: START, PASS, FAIL, SKIP, INFO.
    A START followed by a PASS/FAIL with the same label closes it."""
    sys.stdout.write("@@STEP|%s|%s|%s\n" % (label, status, str(detail).replace("\n", " ")))
    sys.stdout.flush()


# --- script starts here ---
