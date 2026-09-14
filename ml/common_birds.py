"""Target species list for whatsBird's first shippable classifier.

Scope decision: birds a user in mainland China is actually likely to point a phone at — city
parks, gardens, riversides and the commoner wetland species — rather than a taxonomic slice.

Order matters. The class index of every entry *is* the index the classifier emits, and
`species.json` is generated from this list, so reordering it invalidates any trained model.

Licence policy: training images are pulled from iNaturalist and filtered to licences that permit
redistribution of a derived model (CC0 / CC-BY / CC-BY-SA) unless `--allow-noncommercial` is
passed. See ml/README.md for the reasoning.
"""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class TargetSpecies:
    scientific_name: str
    chinese_name: str
    english_name: str
    #: Rough habitat bucket, used only to keep the list balanced when reporting coverage.
    group: str


TARGET_SPECIES: tuple[TargetSpecies, ...] = (
    # --- City parks, gardens and street trees ------------------------------------------------
    TargetSpecies("Pycnonotus sinensis", "白头鹎", "Light-vented Bulbul", "urban"),
    TargetSpecies("Spilopelia chinensis", "珠颈斑鸠", "Spotted Dove", "urban"),
    TargetSpecies("Streptopelia orientalis", "山斑鸠", "Oriental Turtle Dove", "urban"),
    TargetSpecies("Passer montanus", "麻雀", "Eurasian Tree Sparrow", "urban"),
    TargetSpecies("Turdus mandarinus", "乌鸫", "Chinese Blackbird", "urban"),
    TargetSpecies("Copsychus saularis", "鹊鸲", "Oriental Magpie-Robin", "urban"),
    TargetSpecies("Acridotheres cristatellus", "八哥", "Crested Myna", "urban"),
    TargetSpecies("Spodiopsar cineraceus", "灰椋鸟", "White-cheeked Starling", "urban"),
    TargetSpecies("Gracupica nigricollis", "黑领椋鸟", "Black-collared Starling", "urban"),
    TargetSpecies("Cyanopica cyanus", "灰喜鹊", "Azure-winged Magpie", "urban"),
    TargetSpecies("Pica serica", "喜鹊", "Oriental Magpie", "urban"),
    TargetSpecies("Urocissa erythroryncha", "红嘴蓝鹊", "Red-billed Blue Magpie", "urban"),
    TargetSpecies("Parus minor", "远东山雀", "Japanese Tit", "urban"),
    TargetSpecies("Pycnonotus jocosus", "红耳鹎", "Red-whiskered Bulbul", "urban"),
    TargetSpecies("Pycnonotus xanthorrhous", "黄臀鹎", "Brown-breasted Bulbul", "urban"),
    TargetSpecies("Zosterops japonicus", "暗绿绣眼鸟", "Warbling White-eye", "urban"),
    TargetSpecies("Alcedo atthis", "普通翠鸟", "Common Kingfisher", "urban"),
    TargetSpecies("Upupa epops", "戴胜", "Eurasian Hoopoe", "urban"),
    TargetSpecies("Picus canus", "灰头绿啄木鸟", "Grey-headed Woodpecker", "urban"),

    # --- Songbirds of hedges, scrub and farmland ----------------------------------------------
    TargetSpecies("Motacilla alba", "白鹡鸰", "White Wagtail", "songbird"),
    TargetSpecies("Motacilla cinerea", "灰鹡鸰", "Grey Wagtail", "songbird"),
    TargetSpecies("Hirundo rustica", "家燕", "Barn Swallow", "songbird"),
    TargetSpecies("Cecropis daurica", "金腰燕", "Red-rumped Swallow", "songbird"),
    TargetSpecies("Chloris sinica", "金翅雀", "Oriental Greenfinch", "songbird"),
    TargetSpecies("Aegithalos concinnus", "红头长尾山雀", "Black-throated Tit", "songbird"),
    TargetSpecies("Garrulax canorus", "画眉", "Chinese Hwamei", "songbird"),
    TargetSpecies("Pterorhinus sannio", "白颊噪鹛", "White-browed Laughingthrush", "songbird"),
    TargetSpecies("Sinosuthora webbiana", "棕头鸦雀", "Vinous-throated Parrotbill", "songbird"),
    TargetSpecies("Oriolus chinensis", "黑枕黄鹂", "Black-naped Oriole", "songbird"),
    TargetSpecies("Lanius cristatus", "红尾伯劳", "Brown Shrike", "songbird"),
    TargetSpecies("Lanius schach", "棕背伯劳", "Long-tailed Shrike", "songbird"),
    TargetSpecies("Dicrurus macrocercus", "黑卷尾", "Black Drongo", "songbird"),
    TargetSpecies("Corvus macrorhynchos", "大嘴乌鸦", "Large-billed Crow", "songbird"),
    TargetSpecies("Turdus eunomus", "乌灰鸫", "Dusky Thrush", "songbird"),
    TargetSpecies("Turdus naumanni", "红尾鸫", "Naumann's Thrush", "songbird"),
    TargetSpecies("Phoenicurus auroreus", "北红尾鸲", "Daurian Redstart", "songbird"),
    TargetSpecies("Emberiza cioides", "三道眉草鹀", "Meadow Bunting", "songbird"),
    TargetSpecies("Lonchura punctulata", "斑文鸟", "Scaly-breasted Munia", "songbird"),
    TargetSpecies("Columba livia", "原鸽", "Rock Pigeon", "songbird"),

    # --- Water and waterside ----------------------------------------------------------------
    TargetSpecies("Ardea cinerea", "苍鹭", "Grey Heron", "water"),
    TargetSpecies("Egretta garzetta", "白鹭", "Little Egret", "water"),
    TargetSpecies("Ardeola bacchus", "池鹭", "Chinese Pond Heron", "water"),
    TargetSpecies("Bubulcus ibis", "牛背鹭", "Cattle Egret", "water"),
    TargetSpecies("Nycticorax nycticorax", "夜鹭", "Black-crowned Night Heron", "water"),
    TargetSpecies("Tachybaptus ruficollis", "小鸊鷉", "Little Grebe", "water"),
    TargetSpecies("Fulica atra", "骨顶鸡", "Eurasian Coot", "water"),
    TargetSpecies("Gallinula chloropus", "黑水鸡", "Common Moorhen", "water"),
    TargetSpecies("Anas platyrhynchos", "绿头鸭", "Mallard", "water"),
    TargetSpecies("Anas zonorhyncha", "斑嘴鸭", "Eastern Spot-billed Duck", "water"),
    TargetSpecies("Chroicocephalus ridibundus", "红嘴鸥", "Black-headed Gull", "water"),
    TargetSpecies("Milvus migrans", "黑鸢", "Black Kite", "water"),
)

#: Label used for "a bird, but not one of the above". Trained on held-out bird photos, other
#: animals and vegetation so the model has somewhere to put an unknown bird instead of forcing it
#: into the nearest listed species.
BACKGROUND_LABEL = "__background__"

#: Birds that a Chinese user will absolutely encounter but that are deliberately *not* in the target
#: list. They exist to populate the background class: without them the model learns "always pick one
#: of the 52", which is the single most damaging failure mode for a field identifier.
DISTRACTOR_BIRDS: tuple[str, ...] = (
    "Pterorhinus albogularis",
    "Pterorhinus chinensis",
    "Dendrocitta formosae",
    "Leiothrix lutea",
    "Phylloscopus proregulus",
    "Muscicapa dauurica",
    "Ficedula zanthopygia",
    "Anthus hodgsoni",
    "Emberiza spodocephala",
    "Emberiza pusilla",
    "Emberiza elegans",
    "Passer cinnamomeus",
    "Chloropsis hardwickii",
    "Hemixos castanonotus",
    "Ixos mcclellandii",
    "Aethopyga christinae",
    "Lonchura striata",
    "Hypsipetes leucocephalus",
    "Turdus hortulorum",
    "Turdus pallidus",
    "Fringilla montifringilla",
    "Eophona migratoria",
    "Spinus spinus",
    "Emberiza tristrami",
    "Ardea alba",
    "Ardea intermedia",
    "Butorides striata",
    "Ixobrychus sinensis",
    "Acrocephalus orientalis",
    "Tringa ochropus",
    "Actitis hypoleucos",
    "Charadrius dubius",
    "Vanellus cinereus",
    "Recurvirostra avosetta",
)

#: Non-bird taxa. The detector occasionally fires on a clump of leaves or a distant mammal, and the
#: background class needs examples of those too, otherwise it only ever sees birds.
NON_BIRD_TAXA: tuple[str, ...] = (
    "Plantae",
    "Insecta",
    "Mammalia",
    "Fungi",
    "Arachnida",
    "Crustacea",
)



def class_names() -> list[str]:
    """Class order as the classifier sees it: targets first, background last."""
    return [s.scientific_name for s in TARGET_SPECIES] + [BACKGROUND_LABEL]


def chinese_name_for(scientific_name: str) -> str | None:
    for species in TARGET_SPECIES:
        if species.scientific_name == scientific_name:
            return species.chinese_name
    return None
