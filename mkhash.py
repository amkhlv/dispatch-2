#!/usr/bin/env python3

import bcrypt; 
import sys;

print(bcrypt.hashpw(sys.argv[1].encode('utf-8'), bcrypt.gensalt()).decode('ascii'))
