"""Build a traceable subset from the official USDA SR Legacy CSV ZIP. No invented values."""
import csv, io, json, zipfile, sys, hashlib
from pathlib import Path
root=Path(__file__).resolve().parents[1]
archive=Path(sys.argv[1]);z=zipfile.ZipFile(archive)
def rows(name):
    return csv.DictReader(io.TextIOWrapper(z.open(next(n for n in z.namelist() if n.endswith('/'+name)))))
foods={r['fdc_id']:r for r in rows('food.csv')}
energy={r['fdc_id']:float(r['amount']) for r in rows('food_nutrient.csv') if r['nutrient_id']=='1008' and r['amount']}
result=[]
for line in (root/'data/food-mapping.tsv').read_text().splitlines():
    ident,name,group,aliases=line.split('\t');f=foods[ident];value=energy[ident]
    assert 0<=value<=900
    result.append(dict(id=ident,name=name,group=group,aliases=aliases,description=f['description'],kcal100=value,source='USDA SR Legacy 2018',url='https://fdc.nal.usda.gov/food-details/'+ident+'/nutrients'))
output=dict(version='USDA-SR-2018-curated-2026-10-27',archive_sha256=hashlib.sha256(archive.read_bytes()).hexdigest(),download='https://fdc.nal.usda.gov/fdc-datasets/FoodData_Central_sr_legacy_food_csv_2018-04.zip',foods=result)
(root/'app/src/main/assets/foods.json').write_text(json.dumps(output,ensure_ascii=False,indent=2))
print(len(result),'verified source rows')
