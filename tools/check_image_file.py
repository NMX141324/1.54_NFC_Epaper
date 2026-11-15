"""Verify an E6IM upload/readback file. Usage: python tools/check_image_file.py file.bin"""
import argparse
import binascii
import hashlib
from pathlib import Path
import struct

def check(data):
    if data[:2] in (b'\x86\x81',b'\x82\x81') and data[2:6]==b'E6IM':
        data=data[2:]
    if len(data)!=28832: raise ValueError(f'expected 28832 bytes, received {len(data)}')
    magic,version,fmt,header,w,h,n,image_id,crc,flags=struct.unpack('<4sBBHHHIIII',data[:28])
    if (magic,version,fmt,header,w,h,n,flags)!=(b'E6IM',1,1,32,240,240,28800,0):
        raise ValueError('invalid E6IM header/size/pixel format')
    if binascii.crc32(data[:28])!=struct.unpack('<I',data[28:32])[0]: raise ValueError('header CRC mismatch')
    if binascii.crc32(data[32:])!=crc: raise ValueError('pixel CRC mismatch')
    if any((b>>4) not in (0,1,2,3,5,6) or (b&15) not in (0,1,2,3,5,6) for b in data[32:]):
        raise ValueError('invalid six-colour pixel code')
    return image_id,crc,hashlib.sha256(data).hexdigest()

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('file',type=Path);a=p.parse_args()
    image_id,crc,sha=check(a.file.read_bytes())
    print(f'PASS: image={image_id}, 240x240, pixels=28800 bytes, CRC32={crc:08X}, SHA256={sha}')
