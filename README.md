<div align="center">
  <img src="https://github.com/Dayti-0/Kira-Dueller-Hypixel-Bot/blob/main/kira.png" alt="Kira Logo" width="300" height="600">
  <h1>🤖 Kira Dueller — Bot Hypixel Avancé</h1>
  <p><em>Bot intelligent pour les Duels Hypixel avec IA avancée et fonctionnalités premium</em></p>

  <p>
    <a href="https://github.com/Dayti-0/Kira-Dueller-Hypixel-Bot/releases">
      <img src="https://img.shields.io/badge/Download-Latest%20Release-2ea44f?style=for-the-badge" alt="Latest Release">
    </a>
    <img src="https://img.shields.io/badge/version-1.0-blue.svg?style=for-the-badge" alt="Version">
    <img src="https://img.shields.io/badge/Minecraft-1.8.9-brightgreen.svg?style=for-the-badge" alt="Minecraft">
    <img src="https://img.shields.io/badge/Server-Hypixel-orange.svg?style=for-the-badge" alt="Server">
    <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg?style=for-the-badge" alt="Language">
  </p>
</div>

---

## 🎯 À Propos

**Kira Dueller** est un bot de nouvelle génération conçu pour dominer les **Duels Hypixel**.  
Optimisé pour la 1.8.9, il combine **mécaniques de combat** avancées, **automatisations** utiles et **mesures anti-détection** soignées.

### 🌟 Pourquoi choisir Kira ?
- 🧠 **Mécaniques de combat optimisées**
- 🛡️ **Anti-détection** multi-couches
- ⚡ **Performance** & réactivité
- 🎮 **Interface** simple et claire
- 🔄 **Mises à jour** régulières

---

## 🎮 Modes de Jeu Supportés

<table align="center">
<tr>
<td align="center" width="14%">
  <img src="https://img.shields.io/badge/SUMO-✅-success?style=for-the-badge" alt="Sumo">
</td>
<td align="center" width="14%">
  <img src="https://img.shields.io/badge/CLASSIC-✅-success?style=for-the-badge" alt="Classic">
</td>
<td align="center" width="14%">
  <img src="https://img.shields.io/badge/BOXING-✅-success?style=for-the-badge" alt="Boxing">
</td>
<td align="center" width="14%">
  <img src="https://img.shields.io/badge/OP-✅-success?style=for-the-badge" alt="OP">
</td>
<td align="center" width="14%">
  <img src="https://img.shields.io/badge/COMBO-✅-success?style=for-the-badge" alt="Combo">
</td>
<td align="center" width="14%">
  <img src="https://img.shields.io/badge/Bow-✅-success?style=for-the-badge" alt="Bow">
</td>
<td align="center" width="14%">
  <img src="https://img.shields.io/badge/Blitz-✅-success?style=for-the-badge" alt="Blitz">
</td>
</tr>
</table>

---

## 🚀 Fonctionnalités

### 🎯 Combat Intelligent
- **Smart Aim** — visée prédictive avec compensation de latence  
- **Smart Strafe** — *(en cours de dev)*  
- **W-Tap optimisé** — timings de recul et d’engage
- **Combo System** — enchaînements intelligents
- **Distance-based Look Randomization** — moins de jitter à courte portée, effet complet à longue distance

### 🛡️ Sécurité & Furtivité
- **Anti-détection**
- **Randomisation** des actions (human-like)
- **Masquage** dans la liste des mods

### 🔧 Automatisation
- **Auto-requeue** intelligent  
- **Gestion d’inventaire** et **projectiles**  
- **Mouvements pré-match** automatisés 
---

## 📥 Installation

### 🔧 Prérequis
- ✅ Minecraft **1.8.9**  
- ✅ **Forge**

### 🚀 Télécharger & Installer
1. 👉 **[Télécharger la dernière release](https://github.com/Dayti-0/Kira-Dueller-Hypixel-Bot/releases)**  
2. Placer le fichier `.jar` dans le dossier `mods/`.  
3. Lancer Minecraft **1.8.9** avec **Forge**.

### ⚙️ Configuration rapide
- Ouvrir le menu de Kira : assigne un bind dans les **paramètres Minecraft**.  
- Activer/Désactiver le bot : choisis une touche dédiée.

---

## 🧩 Réglages conseillés

```yaml
Combat:
  CPS: 12-16
  Look Speed:
    Horizontal: 10
    Vertical: 5
  Randomization: 0.3

  Lobby Movement: true
  Fast Requeue: true
```

> Par défaut, la randomisation démarre à 5 blocs et atteint son effet maximal à 15 blocs.

---

## 📈 Statistiques & Performance

<div align="center">
  <table>
    <tr>
      <td align="center" width="50%">
        <h3>🏆 Ratio de Victoire</h3>
        <h2>2.80/3.30</h2>
        <p><em>Moyenne indicative sur l’ensemble des modes</em></p>
      </td>
      <td align="center" width="50%">
        <h3>🛡️ Anti-Détection</h3>
        <h2>99%</h2>
        <p><em>Taux de non-détection estimé avec réglages conseillés</em></p>
      </td>
    </tr>
  </table>
</div>

### ⚔️ Gestion intelligente des objets
- **Épée 🗡️** — *parade automatique* pour bloquer les flèches adverses  
- **Arc & canne à pêche 🎯** — pression et contrôle de distance  
- **Enderpearl 🌀** — repositionnement/téléportation tactique (délai de 11 s entre utilisations)
- **Pomme dorée 🍏** — activation au bon timing pour la régénération

---

## 🔧 Développement

### 📁 Structure du projet
```
Kira-Dueller-Hypixel-Bot/
├── src/main/kotlin/
│   ├── bot/           # Logique des bots
│   ├── core/          # Configuration & keybinds
│   ├── gui/           # Interface utilisateur
│   ├── utils/         # Utilitaires
├── src/main/resources/
├── build.gradle.kts
└── README.md
```

### 🛠️ Stack
- **Kotlin** • **Minecraft Forge** • **Mixin** • **Gradle**


## ⚠️ Avertissements

> **Utilisation à vos risques.**  
> L’usage de bots peut enfreindre les **conditions d’utilisation d’Hypixel**.  
> Malgré les systèmes anti-détection, aucune garantie de sécurité totale.  
> Les développeurs ne sont **pas responsables** des sanctions éventuelles.

Mesures intégrées : randomisation, comportements human-like, délais variables, masquage de signature, failsafe auto.

---


## 👥 Équipe de Développement

<div align="center">
  <table>
    <tr>
      <td align="center">
        <img src="https://avatars.githubusercontent.com/Dayti-0?v=1" width="100" alt="Nix" style="border-radius:50%;">
        <br>
        <strong>Nix</strong>
        <br>
        <em>🚀 Lead Developer (Kira Dueller)</em>
        <br>
        <em>Refonte & nouvelles fonctionnalités</em>
      </td>
      <td align="center">
        <img src="https://avatars.githubusercontent.com/HumanDuck23?v=1" width="100" alt="HumanDuck23" style="border-radius:50%;">
        <br>
        <strong>HumanDuck23</strong>
        <br>
        <em>🎯 Créateur du projet original (DuckDueller)</em>
        <br>
        <em>Base & inspiration</em>
      </td>
    </tr>
  </table>
</div>

---

## 🙏 Crédits

**Remerciements :** merci aux contributeurs, testeurs et à la communauté Minecraft ❤️

<p align="center"><em>Dernière mise à jour : Août 2025</em></p>

---

<div align="center">
  <h2>🚀 Prêt à dominer les Duels ?</h2>
  <p><strong><a href="https://github.com/Dayti-0/Kira-Dueller-Hypixel-Bot/releases">📥 Télécharger Kira Maintenant</a></strong></p>
</div>
