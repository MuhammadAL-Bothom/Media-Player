# 🎶 Media2 – Music Player

**Media2** is a simple, lightweight Android **music player app** built in Java.  
It allows users to play audio files from their device with a clean UI and essential controls.

---

## 📱 Screenshots

<p align="center">
  <img src="https://github.com/user-attachments/assets/8f724663-b19d-4c72-9ca8-4b940c515e90" alt="Now Playing screen" width="250"/>
</p>

---

## ✨ Features
- ▶️ Play / Pause / Stop audio  
- ⏮ Previous / ⏭ Next track  
- 📂 Browse music from device storage  
- 🔀 Shuffle & 🔁 Repeat modes  
- 🎚 Volume control & seek bar for playback position  
- 🎨 Modern Material UI  
- 📱 Works offline (no internet required)  

---

## 🛠 Tech Stack
- **Language:** Java (Android)  
- **UI:** AppCompat / Material Components  
- **Media API:** `MediaPlayer` (Android framework)  
- **Storage:** Reads local audio files from device  

---

## 📂 Project Structure
```plaintext
app/
 ├─ src/main/java/com/example/media2/
 │   ├─ MainActivity.java        # Library / song list
 │   ├─ PlayerActivity.java      # Now Playing screen with controls
 │   ├─ adapters/                # List adapters for RecyclerViews
 │   ├─ models/Song.java         # Song data model
 │   └─ utils/                   # Helpers (permissions, formatting)
 │
 ├─ res/layout/                  # XML layouts (player, list, etc.)
 ├─ res/drawable/                # Icons and graphics
 ├─ res/raw/                     # (Optional) test audio files
 ├─ AndroidManifest.xml
 └─ README.md
