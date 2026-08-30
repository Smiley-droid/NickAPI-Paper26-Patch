# Patch NickAPI 7.11 pour Paper 26.2

## Le problème

Sur Paper 26.2, NickAPI 7.11 refuse de s'activer avec l'erreur :
```
[NickAPI] Error has been detected
[NickAPI] Error is not compatible to the NickAPI version you are using
```

Ce n'est **pas** un message d'erreur générique : `"Error"` est littéralement le nom de version
calculé par `NickPlugin.setNmsVersion()`.

### Cause exacte

```java
versionTemp = Bukkit.getServer().getClass().getPackage().getName().split("\\.")[3];
```

Sur Paper 26.2, le package de `CraftServer` n'a plus de segment de version
(`org.bukkit.craftbukkit.CraftServer` au lieu de `org.bukkit.craftbukkit.v1_21_R7.CraftServer`),
donc `split("\\.")[3]` lève une `ArrayIndexOutOfBoundsException`. Le code retombe alors sur une
liste de préfixes de version en dur (`"1.21.11"`, `"1.21.10"`, ...) qui ne couvre pas `"26.x"`,
d'où `versionTemp = "Error"`.

## Le correctif

Paper a annoncé qu'il n'y a **aucun changement d'API/NMS significatif depuis 1.21.11**. Le patch
ajoute donc simplement une branche reconnaissant les versions `26.x` et les redirigeant vers le
même handler déjà utilisé pour 1.21.11 (`Handler_v1_21_R7`), au lieu de tomber sur la branche
"Error" :

```java
if (bukkitVersion.startsWith("26.")) {
    versionTemp = "v1_21_R7";
    this.bypassGameProfile = true;
}
```

Aucun autre fichier n'est modifié. En particulier, `Handler_v1_21_R7` (qui contient tout l'accès
NMS bas niveau — paquets, GameProfile, etc.) reste **le bytecode original, non recompilé**. Paper
dispose d'un système de remapping intégré qui traduit automatiquement les références aux
anciennes classes CraftBukkit versionnées vers les vraies classes internes de la version en cours
— c'est prévu pour exactement ce genre de cas, et c'est ce qui permet à ce handler de continuer à
fonctionner sans recompilation.

## Pourquoi une seule classe recompilée ?

`NickPlugin.java` n'utilise que l'API Bukkit standard (`JavaPlugin`, `Bukkit`, `Listener`) —
aucun accès NMS obfusqué. Il peut donc être recompilé avec un simple `paper-api`, sans avoir
besoin de faire tourner Spigot BuildTools ni de reproduire le pipeline de ré-obfuscation
(`specialsource-maven-plugin`) qu'utilisent les modules `v1_21_R5/R6/R7` du projet d'origine pour
leur propre code NMS.

Le workflow CI compile uniquement cette classe, puis la réinjecte dans une **copie** du jar
original fourni (`original-jar/NickAPI-7.11-original.jar`) — tout le reste du jar (handlers NMS,
dépendances httpclient5 embarquées, etc.) reste strictement identique au jar officiel.

## Utilisation

1. Récupère `NickAPI-7.11-paper26.jar` dans l'onglet **Actions** de ce dépôt (artifact de la
   dernière run réussie).
2. Remplace ton `plugins/NickAPI-7.11.jar` par ce fichier.
3. Redémarre le serveur.

## ⚠️ Statut

Le patch compile (vérifié en CI). Le comportement en jeu sur un vrai serveur Paper 26.2 reste à
confirmer — si `Handler_v1_21_R7` rencontre un souci au runtime (champ/méthode introuvable malgré
le remapping Paper), il faudra alors envisager le plan B : générer un vrai handler dédié pour
26.2 via Spigot BuildTools.

## Origine

Patch basé sur le code source décompilé de NickAPI 7.11 (Haoshoku), fourni par l'administrateur
du serveur avec autorisation d'utilisation.
