"""Create deterministic six-colour 240x240 test images and E6IM transfer files.

Uses only Python's standard library; PNG previews decode the exact packed pixels.
"""
import binascii
import hashlib
import json
from pathlib import Path
import struct
import zlib

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'output/image_test'
PALETTE = {0: (25, 28, 29), 1: (250, 248, 237), 2: (244, 204, 42),
           3: (199, 52, 48), 5: (46, 83, 171), 6: (49, 137, 80)}
FONT = {
 ' ': ('00000',)*7,
 'N': ('10001','11001','10101','10011','10001','10001','10001'),
 'F': ('11111','10000','10000','11110','10000','10000','10000'),
 'C': ('01111','10000','10000','10000','10000','10000','01111'),
 'T': ('11111','00100','00100','00100','00100','00100','00100'),
 'E': ('11111','10000','10000','11110','10000','10000','11111'),
 'S': ('01111','10000','10000','01110','00001','00001','11110'),
 'I': ('11111','00100','00100','00100','00100','00100','11111'),
 'M': ('10001','11011','10101','10101','10001','10001','10001'),
 'G': ('01111','10000','10000','10111','10001','10001','01111'),
 'A': ('01110','10001','10001','11111','10001','10001','10001'),
 'K': ('10001','10010','10100','11000','10100','10010','10001'),
 'B': ('11110','10001','10001','11110','10001','10001','11110'),
 '0': ('01110','10001','10011','10101','11001','10001','01110'),
 '1': ('00100','01100','00100','00100','00100','00100','01110'),
 '2': ('01110','10001','00001','00010','00100','01000','11111'),
 '3': ('11110','00001','00001','01110','00001','00001','11110'),
 '4': ('00010','00110','01010','10010','11111','00010','00010'),
 '5': ('11111','10000','10000','11110','00001','00001','11110'),
 '6': ('01110','10000','10000','11110','10001','10001','01110'),
 '7': ('11111','00001','00010','00100','01000','01000','01000'),
 '8': ('01110','10001','10001','01110','10001','10001','01110'),
 '9': ('01110','10001','10001','01111','00001','00001','01110'),
 'X': ('10001','10001','01010','00100','01010','10001','10001'),
 '.': ('00000','00000','00000','00000','00000','00100','00100'),
}

def png(pixels, scale=1):
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', binascii.crc32(kind+data))
    raw = bytearray()
    for row in pixels:
        line = b'\0' + b''.join(bytes(PALETTE[p])*scale for p in row)
        raw.extend(line*scale)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB',240*scale,240*scale,8,2,0,0,0))
            + chunk(b'IDAT',zlib.compress(raw,9)) + chunk(b'IEND',b''))

def create(image_id):
    p = [[1]*240 for _ in range(240)]
    def rect(x,y,w,h,c):
        for yy in range(max(0,y),min(240,y+h)):
            for xx in range(max(0,x),min(240,x+w)): p[yy][xx]=c
    def text(s,x,y,scale=2,c=0):
        for ch in s:
            for yy,row in enumerate(FONT[ch]):
                for xx,b in enumerate(row):
                    if b=='1': rect(x+xx*scale,y+yy*scale,scale,scale,c)
            x+=6*scale
    rect(0,0,240,240,0);rect(3,3,234,234,1)
    text('NFC TEST',24,12,4)
    text('IMAGE '+str(image_id),24,49,2)
    colors=(0,1,2,3,5,6)
    for i,c in enumerate(colors):
        rect(12+i*36,74,36,28,c)
        text(str(c),24+i*36,110,2,0)
    # Deliberately asymmetric shapes make orientation and nibble order visible.
    for y in range(134,183):
        for x in range(12,120):
            p[y][x]=colors[((x-12)//9+(y-134)//7+image_id)%6]
    for y in range(136,182):
        for x in range(136,225):
            if (x-179)**2+(y-158)**2<22**2: p[y][x]=3 if image_id==1 else 5
    rect(178,138,3,40,1);rect(160,157,39,3,1)
    for i,c in enumerate(colors): rect(12+i*36,192,35,6,c)
    text('240X240',12,208,2)
    text('28.8 KB',132,208,2)
    raw=bytes((row[x]<<4)|row[x+1] for row in p for x in range(0,240,2))
    crc=binascii.crc32(raw)
    header=struct.pack('<4sBBHHHIIII',b'E6IM',1,1,32,240,240,len(raw),image_id,crc,0)
    assert len(header)==28
    blob=header+struct.pack('<I',binascii.crc32(header))+raw
    # Preview is reconstructed from the bytes that are actually transferred.
    decoded=[[((raw[y*120+x//2]>>4) if x%2==0 else (raw[y*120+x//2]&15)) for x in range(240)] for y in range(240)]
    name=f'nfc_sixcolor_test_{image_id:02d}'
    (OUT/(name+'.bin')).write_bytes(blob)
    (OUT/(name+'_pixels.bin')).write_bytes(raw)
    (OUT/(name+'_preview.png')).write_bytes(png(decoded,3))
    (OUT/(name+'_240.png')).write_bytes(png(decoded))
    return {'file':name+'.bin','width':240,'height':240,'image_id':image_id,
            'file_bytes':len(blob),'pixel_bytes':len(raw),'pixel_crc32':f'{crc:08X}',
            'sha256':hashlib.sha256(blob).hexdigest()}

if __name__=='__main__':
    OUT.mkdir(parents=True,exist_ok=True)
    info=[create(1),create(2)]
    (OUT/'manifest.json').write_text(json.dumps(info,indent=2)+'\n')
    print(json.dumps(info,indent=2))
