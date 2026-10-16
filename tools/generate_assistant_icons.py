"""Generate the assistant's small, consistent 24-unit outline icon atlas."""
from pathlib import Path
from PIL import Image, ImageDraw
import math

names = ['book', 'bookmark', 'history', 'changes', 'hud', 'settings', 'close', 'search', 'copy', 'external', 'chevron', 'refresh', 'check', 'trash', 'move', 'sliders', 'arrow']
cell, factor = 64, 8
atlas = Image.new('RGBA', (cell * len(names), cell))
for i, name in enumerate(names):
    image = Image.new('RGBA', (24 * factor, 24 * factor))
    d = ImageDraw.Draw(image)
    def line(points, width=1.7):
        xy = [(round(x*factor),round(y*factor)) for x,y in points]
        stroke = round(width*factor)
        d.line(xy, fill='white', width=stroke, joint='curve')
        r = stroke / 2
        for x,y in xy:
            d.ellipse((x-r,y-r,x+r,y+r), fill='white')
    def circle(x,y,r):
        d.ellipse(((x-r)*factor,(y-r)*factor,(x+r)*factor,(y+r)*factor), outline='white', width=round(1.7*factor))
    def rect(x,y,w,h,r=2):
        d.rounded_rectangle((x*factor,y*factor,(x+w)*factor,(y+h)*factor), radius=r*factor, outline='white', width=round(1.7*factor))
    if name == 'book':
        line([(12,5),(8,3),(3,3),(3,19),(8,19),(12,21),(16,19),(21,19),(21,3),(16,3),(12,5),(12,21)])
    elif name == 'bookmark': line([(6,21),(6,4),(7,3),(17,3),(18,4),(18,21),(12,17),(6,21)])
    elif name == 'history':
        d.arc((4*factor,4*factor,21*factor,21*factor),195,530,fill='white',width=round(1.7*factor))
        line([(3,4),(3,9),(8,9)]);line([(12,7),(12,12),(15,14)])
    elif name == 'changes':
        line([(14,3),(5,3),(5,21),(19,21),(19,8),(14,3),(14,8),(19,8)])
        line([(8,13),(15,13)]);line([(8,17),(13,17)])
    elif name == 'hud':
        rect(3,4,18,16);line([(3,10),(21,10)]);line([(10,10),(10,20)])
    elif name == 'settings':
        for y,knob in [(6,9),(12,16),(18,7)]:
            line([(3,y),(knob-2,y)]);line([(knob+2,y),(21,y)]);circle(knob,y,2)
    elif name == 'close': line([(6,6),(18,18)]);line([(18,6),(6,18)])
    elif name == 'search': circle(10.5,10.5,6.5);line([(15.5,15.5),(21,21)])
    elif name == 'copy':
        rect(8,8,12,13);line([(16,4),(16,3),(4,3),(3,4),(3,16),(4,16)])
    elif name == 'external':
        line([(10,4),(4,4),(3,5),(3,20),(4,21),(19,21),(20,20),(20,14)])
        line([(14,3),(21,3),(21,10)]);line([(11,13),(21,3)])
    elif name == 'chevron': line([(6,9),(12,15),(18,9)])
    elif name == 'refresh':
        d.arc((4*factor,4*factor,20*factor,20*factor),35,340,fill='white',width=round(1.7*factor))
        line([(21,4),(21,10),(15,10)])
    elif name == 'check': line([(5,12),(10,17),(20,6)])
    elif name == 'trash':
        line([(3,6),(21,6)]);line([(8,6),(8,3),(16,3),(16,6)])
        line([(5,6),(6,21),(18,21),(19,6)]);line([(10,10),(10,17)]);line([(14,10),(14,17)])
    elif name == 'move':
        line([(12,3),(12,21)]);line([(3,12),(21,12)])
        line([(8,7),(12,3),(16,7)]);line([(8,17),(12,21),(16,17)])
        line([(7,8),(3,12),(7,16)]);line([(17,8),(21,12),(17,16)])
    elif name == 'sliders':
        line([(6,3),(6,8)]);line([(6,12),(6,21)]);circle(6,10,2)
        line([(12,3),(12,14)]);line([(12,18),(12,21)]);circle(12,16,2)
        line([(18,3),(18,6)]);line([(18,10),(18,21)]);circle(18,8,2)
    elif name == 'arrow': line([(8,5),(15,12),(8,19)])
    atlas.paste(image.resize((cell,cell), Image.Resampling.LANCZOS), (i*cell,0))
path = Path(__file__).resolve().parents[1] / 'client-mod/src/main/resources/assets/awassistant/ui/icons.png'
path.parent.mkdir(parents=True, exist_ok=True)
atlas.save(path, optimize=True)
print(path)

circle_image = Image.new('RGBA', (512,512))
ImageDraw.Draw(circle_image).ellipse((0,0,511,511), fill='white')
circle_image.resize((64,64), Image.Resampling.LANCZOS).save(path.with_name('round.png'))
for name in ['round.png.mcmeta','icons.png.mcmeta']:
    path.with_name(name).write_text('{"texture":{"blur":true,"clamp":true}}\n', encoding='utf-8')
