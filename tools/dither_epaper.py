"""Convert a 24-bit BMP to the exact six-colour E6IM format used by r5.1.

Serpentine Floyd-Steinberg error diffusion in linear-light RGB. No AI is used
for quantization; the preview is reconstructed from the actual packed bytes.
"""
import argparse
import binascii
from collections import Counter
import hashlib
import json
from pathlib import Path
import struct
import zipfile
import zlib
from check_image_file import check
from make_test_image import PALETTE, FONT

def read_bmp(path):
    data=path.read_bytes()
    offset=struct.unpack_from('<I',data,10)[0]
    width,height=struct.unpack_from('<ii',data,18)
    depth=struct.unpack_from('<H',data,28)[0]
    compression=struct.unpack_from('<I',data,30)[0]
    if data[:2]!=b'BM' or (width,abs(height),depth,compression)!=(240,240,24,0):
        raise ValueError('Expected uncompressed 240x240 24-bit BMP')
    stride=(width*3+3)&~3
    pixels=[]
    for y in range(240):
        start=offset+(239-y if height>0 else y)*stride
        pixels.append([tuple(data[start+x*3+i] for i in (2,1,0)) for x in range(240)])
    return pixels

def linear(v):
    s=v/255.0
    return s/12.92 if s<=0.04045 else ((s+0.055)/1.055)**2.4

def quantize(rgb,diffuse):
    codes=tuple(PALETTE)
    palette=[tuple(linear(c) for c in PALETTE[code]) for code in codes]
    lo=[min(p[c] for p in palette) for c in range(3)]
    hi=[max(p[c] for p in palette) for c in range(3)]
    work=[[[max(lo[c],min(hi[c],linear(p[c]))) for c in range(3)] for p in row] for row in rgb]
    result=[[0]*240 for _ in range(240)]
    for y in range(240):
        direction=-1 if y%2 else 1
        for x in (range(239,-1,-1) if direction<0 else range(240)):
            current=[max(lo[c],min(hi[c],work[y][x][c])) for c in range(3)]
            index=min(range(6),key=lambda k:sum((current[c]-palette[k][c])**2 for c in range(3)))
            result[y][x]=codes[index]
            if diffuse:
                error=[current[c]-palette[index][c] for c in range(3)]
                for dx,dy,weight in ((direction,0,7/16),(-direction,1,3/16),(0,1,5/16),(direction,1,1/16)):
                    xx,yy=x+dx,y+dy
                    if 0<=xx<240 and yy<240:
                        for c in range(3): work[yy][xx][c]+=error[c]*weight
    return result

def png(rgb,scale=1):
    width,height=len(rgb[0])*scale,len(rgb)*scale
    def chunk(kind,data):
        return struct.pack('>I',len(data))+kind+data+struct.pack('>I',binascii.crc32(kind+data))
    rows=bytearray()
    for row in rgb:
        line=b'\0'+b''.join(bytes(p)*scale for p in row)
        rows.extend(line*scale)
    return (b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',width,height,8,2,0,0,0))+
            chunk(b'IDAT',zlib.compress(rows,9))+chunk(b'IEND',b''))

def comparison(original,nearest,dither):
    glyphs=dict(FONT)
    glyphs.update({
      'O':('01110','10001','10001','10001','10001','10001','01110'),
      'R':('11110','10001','10001','11110','10100','10010','10001'),
      'L':('10000','10000','10000','10000','10000','10000','11111'),
      'D':('11110','10001','10001','10001','10001','10001','11110'),
      'H':('10001','10001','10001','11111','10001','10001','10001')})
    sheet=[[(255,255,255)]*720 for _ in range(260)]
    for panel,(title,pixels) in enumerate((('ORIGINAL',original),('NO DITHER',nearest),('FS DITHER',dither))):
        left=panel*240
        for y in range(240): sheet[y+20][left:left+240]=pixels[y]
        for i,ch in enumerate(title):
            for yy,row in enumerate(glyphs[ch]):
                for xx,b in enumerate(row):
                    if b=='1': sheet[6+yy][left+8+i*6+xx]=(0,0,0)
    return sheet

def convert(source,out,name,image_id):
    out.mkdir(parents=True,exist_ok=True)
    original=read_bmp(source)
    nearest=quantize(original,False)
    dither=quantize(original,True)
    raw=bytes((row[x]<<4)|row[x+1] for row in dither for x in range(0,240,2))
    crc=binascii.crc32(raw)
    header=struct.pack('<4sBBHHHIIII',b'E6IM',1,1,32,240,240,28800,image_id,crc,0)
    blob=header+struct.pack('<I',binascii.crc32(header))+raw
    assert check(blob)[:2]==(image_id,crc)
    decoded=[[(raw[y*120+x//2]>>4) if x%2==0 else (raw[y*120+x//2]&15) for x in range(240)] for y in range(240)]
    assert decoded==dither
    actual_rgb=[[PALETTE[p] for p in row] for row in decoded]
    nearest_rgb=[[PALETTE[p] for p in row] for row in nearest]
    (out/(name+'.bin')).write_bytes(blob)
    (out/(name+'_240.png')).write_bytes(png(actual_rgb))
    (out/(name+'_preview.png')).write_bytes(png(actual_rgb,3))
    (out/(name+'_nearest.png')).write_bytes(png(nearest_rgb,3))
    (out/(name+'_comparison.png')).write_bytes(png(comparison(original,nearest_rgb,actual_rgb),2))
    (out/(name+'_source_240.png')).write_bytes(png(original))
    counts=Counter(p for row in dither for p in row)
    assert set(counts)==set(PALETTE)
    meta={'file':name+'.bin','image_id':image_id,'width':240,'height':240,
          'algorithm':'serpentine Floyd-Steinberg, linear-light RGB, full error diffusion',
          'palette_rgb':PALETTE,'pixel_counts':dict(sorted(counts.items())),
          'file_bytes':len(blob),'pixel_bytes':len(raw),'pixel_crc32':f'{crc:08X}',
          'sha256':hashlib.sha256(blob).hexdigest(),
          'preview':'decoded from the exact transmitted high-nibble-first packed pixels',
          'firmware':'existing r5.1 FTM E6IM image receiver; no firmware change required'}
    (out/(name+'_manifest.json')).write_text(json.dumps(meta,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(meta,ensure_ascii=False,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('source',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--name',default='kittens_sixcolor_fs_image03');p.add_argument('--id',type=int,default=3)
    a=p.parse_args();convert(a.source,a.output,a.name,a.id)
