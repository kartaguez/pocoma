# Table de récupération des archives retirées du HEAD

Toutes les lignes relèvent de **B — conservation historique Git**, sans externalisation.
Commit historique exact commun : `3c6315cfd52dfee9d764a3cb00c16248793cdd8a`.
Préfixe commun des chemins : `docs/testing/evidence/`. Chaque ligne a été restaurée
sur disque depuis ce commit, puis vérifiée par taille et SHA-256 **avant et après** retrait.
Le SHA testé et les verdicts ne sont pas ce SHA de stockage : consulter les champs
`tested_sha`, `receipt_metadata` et `provenance_receipts` de [l’inventaire](Evidence_Retention.json).

Depuis la racine, avec une destination neuve (aucun écrasement) :

```sh
python3 -B scripts/testing/evidence-storage.py restore docs/testing/evidence/L2-I5/A-9a315e16.tar.xz --destination /private/tmp/A-stored.tar.xz
python3 -B scripts/testing/evidence-storage.py restore docs/testing/evidence/L2-I5/A-9a315e16.tar.gz --destination /private/tmp/A-original.tar.gz
```

La première commande restitue le XZ exact du commit de référence ; la seconde
restitue les octets gzip originaux depuis `9a284bcd765ff1ec4f21d7971cfc65214a1c77de`.
Les 37 alias gzip et leurs empreintes propres sont dans `historical_gzip_aliases`.
La restauration gzip ne dépend plus d’une version de zlib. `resolve` imprime
`git:<commit>:<chemin>` pour les preuves historiques, un chemin physique pour A.
Ne pas transmettre ce localisateur Git directement à `tarfile.open` : appeler
`restore` avant les scripts qui exigent un fichier.

L’équivalent Git brut est `git show <commit>:<chemin> > <destination-neuve>` suivi
d’une comparaison SHA-256. Préférer le script pour son refus des fichiers existants,
sa vérification et son retrait d’une sortie partielle invalide.

| Chemin historique après préfixe commun | Octets | SHA-256 original du fichier retiré |
|---|---:|---|
| `L1-AB/A-raw.tar.gz` | 1706633 | `67fd8541ebe1b3721edfffbcc6a0a3ecd9875c1ee70f39966a16525a472ed426` |
| `L1-AB/B-diagnostics.tar.gz` | 87020 | `e2125df8d9d9d98dbb9c3194425ce4cb86e65aa63e3f4bb32b2488dceef568bc` |
| `L1-AB/B-raw.tar.gz` | 2750380 | `83e08d2b9b0b81f8331acf3af73855b93463bd370a6437cbcb0659b8d5ab2caa` |
| `L1-C/Diagnostics.tar.gz` | 102067 | `9aeb0ea3187c6e42ea7b9b9fa36386af76ba9c2c8af6003c6648131955875852` |
| `L1-C/Gates-raw.tar.gz` | 2404872 | `e95ba0186b03ffcfcc9d1b8c40492f168fba2ee9393e0d6c4e6431980fd20b59` |
| `L1-C/Superseded-raw.tar.gz` | 1856303 | `b925a8732eac6988401eaff4fddcf3a6ac9311d7a7d1a1d2dbce859e32bfe711` |
| `L1-DE/D-diagnostics.tar.gz` | 30775 | `9f8ed28711c1562129f27d68c1dc6fab57db80aeffcc8468492908ea652d3625` |
| `L1-DE/D-gates-raw.tar.gz` | 1897606 | `1f5254fd1e42a1df7561617f2ae2fe323dbfd482803e52df068c15807f2a6778` |
| `L1-DE/E-diagnostics.tar.gz` | 22599 | `a76d4376582ae2130ea4c2c110646954bf16b6a9618c1f1122fdaf2e4ec7eef2` |
| `L1-DE/E-gates-raw.tar.gz` | 3581173 | `af0a6a720710d45fce39ddbf70c62732e1135f300064b42a83fb3698b4cb4ee3` |
| `L1-DE/E-superseded-raw.tar.gz` | 1887464 | `84eeb0b2c3e0c0c8a46243aa396c88e54a59b909d0f17d463ded6048935201c1` |
| `L1-F/diagnostics-raw.tar.gz` | 263023 | `c245907e82776b23970782de2299829c19e8d858ea18ae78b0a51cba2f07f697` |
| `L1-F/gates-raw.tar.gz` | 2446973 | `888e8551e5b8523af7659488317a1acf098d8b613737b4adf74a63ccf190a33c` |
| `L1-F/performance-raw.tar.gz` | 14587 | `1dda1432b56e6861e274e8654ba9e9ceccb79d95d7d4c686d437343c9a35cafb` |
| `L1-F2/before.tar.gz` | 27619 | `4a05d7ed30bb3f1008f8fe7eb8c88de6ea72af15ca0756b221d1a3c6b54b4f8f` |
| `L1-F2/command-1792961062290431000.tar.gz` | 1125208 | `5c03f28ceeae72deafdd84c6bdc1144b95debe0c8cb2d8f1f8bb4cd70823ad6b` |
| `L1-F2/diagnostics.tar.gz` | 75361 | `cc8d64389583a9ec8f0e044a1f477afc4aac85bab3946684e1372bdf6a88b995` |
| `L1-F2/supplemental.tar.gz` | 4616 | `f9ec7621c858d54243a580a416178d53b7faac531f722f9705c0cac4810364d6` |
| `L2-I1/ARCHITECTURE.tar.gz` | 904461 | `cdff7c7d84dc71a9659558495f96e324405a2f834b268add9507b9bbcb0dddaf` |
| `L2-I1/COMMAND.tar.gz` | 614569 | `112a54e7214b71086bf11639a28c8dc734d2c2eb9f43c6cf70f5ce419f8b5a84` |
| `L2-I1/MONOLITH.tar.gz` | 454278 | `483f1e8addc094c8335ebf6f0c546a8216ef34a28134bf11daa7b2d115c955be` |
| `L2-I1/READ.tar.gz` | 57607 | `4189765db725b1f679d7676d5c7cee00e861024ad608eec41ffc19f3b01b1857` |
| `L2-I1/diagnostics.tar.gz` | 686092 | `3c60e31e73abb339d278bb23b4092128cfad3b9c6005f3d3e5088e6c35f0de7e` |
| `L2-I2/Diagnostics.tar.gz` | 266071 | `f97cd9494e723092f319211ba52e525703a3db79584a42436d3847805d07e43d` |
| `L2-I2/Standalone-raw.tar.gz` | 29334 | `aaa9e9be2cff685ea5a0a0e3c1a59861ae6b6b92a9a6ca861c248685e31965b9` |
| `L2-I2/architecture-delivery.tar.gz` | 1377060 | `1df0939cf87f1649ea1e3c82022b8e47874d45408679eba2f06e24ac5010d051` |
| `L2-I2/architecture-final-2.tar.gz` | 1376048 | `fc37f3fe22455103e7ca4bd494141277fd56fc5f63bae9eea493c683477ed9ee` |
| `L2-I2/architecture-final.tar.gz` | 1378229 | `bd9491e9c05f2b67ed10d2060082bfd92f1b44035f5e7f719eab5756bac5e866` |
| `L2-I2/command-1.tar.gz` | 727592 | `46820b095407ef377ac19ec2d3f0602213e0798507069190f07653a924fa0b8b` |
| `L2-I2/command-2.tar.gz` | 1066845 | `0d70f993398056ce144011299e85e2f2348b55b476cda9898f6e177b8a733719` |
| `L2-I2/command-delivery.tar.gz` | 1065692 | `c57a65babea3633b1b012026e93ae3072cbaf5c40faa9f29307312989cf57bb1` |
| `L2-I2/command-final.tar.gz` | 1063372 | `63695f1e9448a89863a2b75c421f9c347eea49f30dfb3619704861e41a0d14ef` |
| `L2-I2/monolith-delivery.tar.gz` | 902438 | `1a876d468f66b249776fac352aa9ada986de044fb15e50f1c0facc6de0088296` |
| `L2-I2/monolith-final.tar.gz` | 901762 | `adf8678c944cf6bef1f78e080afa2361ed60299b30813357c902893bed4ed475` |
| `L2-I2/transactions-delivery.tar.gz` | 41287 | `6357bca5701562c84a2697fddf1032bba2749765906194c96e9fdd5e4c6ab06d` |
| `L2-I2/transactions-final-2.tar.gz` | 46580 | `8ab8ee7f30a1299fc281d51b4812c810240579eea7831e4bcedd2f1197278899` |
| `L2-I2/transactions-final.tar.gz` | 40840 | `37e089555e78121f7e41a281355002023753d4b7db402073d7401ad3d43d2ed7` |
| `L2-I3-post-audit/architecture-3ee57594.tar.gz` | 1504740 | `8e37e57cc20682fe1650ddfc6b3f73fd073a8e1965578580abdaccc78c80e4c5` |
| `L2-I3-post-audit/command-3ee57594.tar.gz` | 1190259 | `7223417bb1afb2a15d0129756f858980464a39c4fb0696886343bcf699f45966` |
| `L2-I3-post-audit/diagnostics.tar.gz` | 48283 | `52613e569046f0c484cf5d0e0749ad14f1267da54302b0a64306ff1c80938f4f` |
| `L2-I3-post-audit/monolith-3ee57594.tar.gz` | 1018160 | `7d47dfe6065ffbf8a6bc221802d040b60c6cfe25106883291269c19db7e13052` |
| `L2-I3-post-audit/read-3ee57594.tar.gz` | 73782 | `bb11d26c93e4ccc662e0fbb248ac37a3030e8c8b635dbcb88fb54df411c92f45` |
| `L2-I3/Diagnostics-raw-logs.tar.gz` | 181536 | `5e34edf89c3690a5310177112aed36059501043e15dba0df8b21cfd8e583e982` |
| `L2-I3/architecture-1d7cf51c.tar.gz` | 1491339 | `f17e7316d1d6f9261153da9476a8c88210677abe7b8a940f40f5221fb2623be1` |
| `L2-I3/command-1d7cf51c.tar.gz` | 1182869 | `b9ecfa271c625cb1f3a08d597e95b22b50d208fb0c6788d8a8e17496809d258b` |
| `L2-I3/command-e9a6848f.tar.gz` | 1181777 | `f77b6ab18042675bb4a6bba831a5a1211c0fda2d65fb4cb6465f32f16f627472` |
| `L2-I3/diagnostics/architecture-bdee72b0-partial.tar.gz` | 211859 | `4f83f01862a587e541a8c67e7248ff48e1e064182415efee103de470c5238764` |
| `L2-I3/diagnostics/architecture-e67011af-failed.tar.gz` | 1494667 | `10ef6d67a8d821596691bd2d56c9728fa94167c9ef57dc541f78064d3dc4af2f` |
| `L2-I3/diagnostics/pg-3.tar.gz` | 61989 | `414cb8da6a49a5b45abaa78f684f5706906b33b6af578f7f04342c125e3c114e` |
| `L2-I3/monolith-1d7cf51c.tar.gz` | 1012460 | `0a6fd89fefe2eb219df85fa27e2e8f7e31764eeb47f988123140af755d44b2a7` |
| `L2-I3/monolith-e9a6848f.tar.gz` | 1007809 | `c178db0cea1478cffed7853a2bcbd6cadd38b9a2f5d453f3a803cbabdbfff81e` |
| `L2-I3/read-1d7cf51c.tar.gz` | 72966 | `804a38c89a45e0aa147f5fdd04a90b5cc6cae2a5d4a90d860af816ae17668c9c` |
| `L2-I3/read-e9a6848f.tar.gz` | 72864 | `a311b56384115cd5732f9c1e912dc6580b4fdfd7f444c9a19507e3bd1b2696eb` |
| `L2-I4/I4-3-initial-command.tar.gz` | 972421 | `af901ddae5db0566215c33b7ce1d147d3e9fa49aa206d85637590bf7cd96ab2e` |
| `L2-I4/I4-4-5-discovery-diagnostic.tar.gz` | 429411 | `6c98745f579179c5225979ff8cf20cd3fde18c85826c0b744c0d3d6ae4bb8072` |
| `L2-I4/I4-6-all49-diagnostic.tar.xz` | 3371292 | `4f2c0d4e6d9100bd3e60bd5d700ffd65940a6e9ff10c69911ac5231ea54b1abc` |
| `L2-I4/I4-7-complements-command.tar.xz` | 15450260 | `878cc141cd57816a60576721c2b5ffdaa7c36754fa8ab1f081b1bc4224042cad` |
| `L2-I4/I4-7-last-variants-diagnostic.tar.xz` | 15768048 | `2c432ba1355407714cb56c972d476379f498eb4a125b33ade24d4b3dc34344cc` |
| `L2-I4/I4-7-last-variants-retry.tar.xz` | 6971724 | `0bb190493b1b4942fb102860880161e6a5be607b280ad68228af61e690943157` |
| `L2-I4/final-architecture.tar.xz` | 16486880 | `f26cab9f7ea06ace6fba60e8c942aefed892782200482d5f72a260b0eb78f16d` |
| `L2-I4/final-command.tar.xz` | 16406324 | `ae888872dde65baee69be0a1deed7a1b02dd3e01713c54cd2a3821fad0bee5db` |
| `L2-I4/final-monolith.tar.xz` | 16348820 | `b1fcc7a5cdaf7986cce9fa49f09a63fbff691223ae6f7320b346cf4888e6c9a0` |
| `L2-I4/final-read.tar.gz` | 187371 | `538ec1928480d548c5c36697fe3bfa09fe8a6e2a4b55ac04a6e0e0924576a1dc` |
| `L2-I5-audit-remediation/deterministic.tar.gz` | 37834 | `6d7689b1da0f072e4874d9a670089b1533a0b203a7ec697d1bef0000ad6470d2` |
| `L2-I5-audit-remediation/gate-1-failed-05b74814.tar.xz` | 19898560 | `b91baeef010548f4ca22eb91f1923a542b871dd3431559bca402a27fc05cb005` |
| `L2-I5/A-9a315e16.tar.xz` | 19625660 | `b37fa57b617be1708c36e4e4af1379c72652fb711275da517773977673f960ed` |
| `L2-I5/C-9a315e16.tar.xz` | 19572752 | `caf7aa57e182585f2c1a61852c9316a4226ebad0e3b7d2814b5dd34ecfd712ec` |
| `L2-I5/F-9a315e16.tar.xz` | 19619660 | `75742bd1da6d776b0b94c3c4566be6da35e2e26e3ef645d42bf58818757afccc` |
| `L2-I5/M-9a315e16.tar.xz` | 19348156 | `9ff8a7e9226291bcb095b686578228739e7ba005340eafa13ae132aa089864fe` |
| `L2-I5/R-9a315e16.tar.gz` | 144697 | `cf29979bfe5a9d8a0c1819b7500a38bf1cb76cfcbf3ed4c3232b2d500e7d8750` |
| `L2-I5/diagnostics/A-22362595-superseded.tar.xz` | 19330512 | `f710f71fb170ea20a4639a3bf09c296a0945741054fc5b402f456b5bf88976fe` |
| `L2-I5/diagnostics/A-3efcb8cc-superseded.tar.xz` | 19585796 | `88014b9d53a35312943053e3f36ed6cb7af8b2d7d52d3315c6ffcf81cc872966` |
| `L2-I5/diagnostics/A-7296e11c-superseded.tar.xz` | 13412968 | `e037a7d6ecc1006fd1ab259393b684c760388aaf4fd9ee0b0d43a0046b21062f` |
| `L2-I5/diagnostics/A-ac9f7642-superseded.tar.xz` | 19463340 | `33a22b9fe0932c9122fe1189a63fb3f47ea859d8069bf1f8b46877c78667bbe8` |
| `L2-I5/diagnostics/A-c8b0815b-superseded.tar.xz` | 19461260 | `c3a37d17b25df5389d23ad3e7f4c1f01d1a82073f71d056edfcfbf430ad8cd1a` |
| `L2-I5/diagnostics/A-e257c381-superseded.tar.xz` | 19609556 | `6f8860eb02d5d9037c79fed37c1b5782500bef4f585f86c315ff6a01e910a083` |
| `L2-I5/diagnostics/C-22362595-superseded.tar.xz` | 19344464 | `41e70be0cb2dcfe6bc7e722fb5f64a1a54a334af9ed300fe2a944a74d712d063` |
| `L2-I5/diagnostics/C-3efcb8cc-superseded.tar.xz` | 19495664 | `12411f7063f44b9165190c45b1dffc4cf7acd27172f6462e3f80a868938fc857` |
| `L2-I5/diagnostics/C-7296e11c-superseded.tar.xz` | 14633124 | `41af5e95a25b8c8bf42ecd61eb1ee766f01244e7a62e538199683198619c5bd2` |
| `L2-I5/diagnostics/C-8e6a3601-interrupted.tar.xz` | 19050604 | `54b67ed8a6d9606f94bd7d8cedb69d69d116bc3cffae22350662ff15ba3d162a` |
| `L2-I5/diagnostics/C-ac9f7642-superseded.tar.xz` | 19428008 | `eafe45065d55fd0eb1e7e54df977126da64dfd56682c8c35df1e6c83262bb8ef` |
| `L2-I5/diagnostics/C-c8b0815b-superseded.tar.xz` | 19428212 | `29ae9d69c56c9f20691d13f5b6095aa05fb8698230d19192a90d4cf95b243f24` |
| `L2-I5/diagnostics/C-e257c381-superseded.tar.xz` | 19530300 | `0d98273f025d7aa44582f50ae546dbae9bacb3aa249b20623b1a72c5cd1e8bee` |
| `L2-I5/diagnostics/F-22362595-superseded.tar.xz` | 19331052 | `1b752ff67c06fa25d8d6fe48807ee8d8b8002f4b9c14f5e1b5d6ba642e8f07a5` |
| `L2-I5/diagnostics/F-3efcb8cc-superseded.tar.xz` | 19594676 | `202160b58082e2312154e7858d07ddc39153cf1cb438008a3b1b348d3bc8e0ab` |
| `L2-I5/diagnostics/F-7296e11c-superseded.tar.xz` | 5596480 | `663f6d24298f10c78006649f85fbaa181aebe6c5110136e5a30cb645d61c0c88` |
| `L2-I5/diagnostics/F-ac9f7642-discarded-provenance.tar.gz` | 364965 | `63728a9739ab6f75de1511fd1f8ba656a000e5666ad07563971c89f9ce56a8c1` |
| `L2-I5/diagnostics/F-ac9f7642-superseded.tar.xz` | 19382636 | `ba6acdc13fdb4f5533167ea6389d76999e3bb6fbd2e3e8a7dab8640f59f2e667` |
| `L2-I5/diagnostics/F-c8b0815b-superseded.tar.xz` | 19342068 | `1e60d82f4bf59b3558f5db258862d8446a55daf87d6485574d434b281c9595a4` |
| `L2-I5/diagnostics/F-e257c381-superseded.tar.xz` | 19562308 | `1b9af9d1a639213c0dbfd44b1390f3e10b7f7078c59b434aa3b01e81637d3d54` |
| `L2-I5/diagnostics/M-22362595-superseded.tar.xz` | 19314472 | `061fe3332a5670c690a49a7899d1236c09ec67fd4f90e56b257c0037e2239a16` |
| `L2-I5/diagnostics/M-3efcb8cc-superseded.tar.xz` | 19372656 | `16f35f8f62bd6f188f3c5228c075a6ef2767d3fc55bc81343dc0be3506f44a2c` |
| `L2-I5/diagnostics/M-7296e11c-superseded.tar.xz` | 10454316 | `bb40405a64cfc1dbbe156daee7533fcbd1a11587ec09d30d6d6a5cf8d5be4a87` |
| `L2-I5/diagnostics/M-ac9f7642-discarded-provenance.tar.gz` | 9949 | `33e0e74e7d4730f5cfd749bdcd9e0d737e97569163b8cd9b31d606f4c6ff0538` |
| `L2-I5/diagnostics/M-ac9f7642-superseded.tar.xz` | 19280744 | `d0e533d3483fed42b95fbf53eea27e4796e6820d7703ebebe64ebac98ab309ce` |
| `L2-I5/diagnostics/M-c8b0815b-superseded.tar.xz` | 19351368 | `f063e891f3c19fa5b7353258d83a27519b4f7cd84492b9ceab421c2f50f59b6d` |
| `L2-I5/diagnostics/M-e257c381-superseded.tar.xz` | 19367100 | `51f8b9b6e9c09b256500b2abb66ec4f0700b6d05690156729898e7afe8c87aef` |
| `L2-I5/diagnostics/R-22362595-superseded.tar.gz` | 145302 | `ad0c88c8868336479b10844198c6c85561febe34b8a195606d4f8d1d59d65e6d` |
| `L2-I5/diagnostics/R-3efcb8cc-superseded.tar.gz` | 145244 | `be4147b4a4d75eeab206d92fa39955f641f1fafbc8af7dd5b7af21fbe4bce278` |
| `L2-I5/diagnostics/R-7296e11c-superseded.tar.gz` | 145084 | `9d6a688328316f4f27acc13b47fe3d1242bb76884d2ac8b97efdc329c2eba8b4` |
| `L2-I5/diagnostics/R-c8b0815b-superseded.tar.gz` | 145039 | `68a43ced4708c4c00fbb0142b96d2c93166d61b610e3792d13f6753ec8610e04` |
| `L2-I5/diagnostics/R-e257c381-superseded.tar.gz` | 145272 | `df8b874fd21ed6bbbe62e1617e7210d0a4d40837ef4a2d2f71d385dd28239f3a` |
| `L2-I5/diagnostics/development-F01-superseded.tar.gz` | 66170 | `5cef62de65b43dc939f2f80bef6dbe928f92141e0239bd3b961727bd5ca4b6b5` |
| `L2-I5/diagnostics/development-F02-superseded.tar.gz` | 66228 | `7de3faf7c1f05d4508835505e674244e50663d9ccb724da88d0461cfdaba3010` |
| `L2-I5/diagnostics/development-F03-superseded.tar.gz` | 70279 | `70b52ba78ed4617ec06564d264e3b811ffb4b7068f4a2c0705116c831b70c030` |
