# QR & Barcode Scanner

A modern, feature-rich Android application for scanning QR codes and barcodes built with Jetpack Compose and CameraX.

![QR Scanner App](https://img.shields.io/badge/Android-8%2B-green) ![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0+-blue) ![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM-orange) ![License](https://img.shields.io/badge/License-MIT-purple)


## 📸 Screenshots

      

## ✨ Features

### 📱 Core Functionality
- **QR Code Scanning** - Fast and accurate QR code detection
- **Barcode Scanning** - Support for various barcode formats (EAN-13, UPC-A, Code 128, etc.)
- **Gallery Scanning** - Scan codes from images in your gallery
- **Smart Detection** - Automatically identifies and categorizes scanned content

### 🎨 User Experience
- **Modern UI** - Clean, Material Design 3 interface
- **Zoom Controls** - Pinch-to-zoom for better scanning accuracy
- **Camera Switch** - Toggle between front and rear cameras
- **Flashlight Toggle** - Built-in flashlight for low-light conditions
- **Scan History** - Keep track of your recent scans (coming soon)

### 🔧 Technical Features
- **CameraX Integration** - Robust camera handling with lifecycle awareness
- **ML Kit** - Google's machine learning for accurate detection
- **Compose Architecture** - Modern declarative UI framework
- **Permission Handling** - Smooth camera permission workflow

## 🛠️ Built With

- **[Jetpack Compose](https://developer.android.com/jetpack/compose)** - Modern UI toolkit
- **[CameraX](https://developer.android.com/training/camerax)** - Camera library
- **[ML Kit](https://developers.google.com/ml-kit)** - Machine learning for barcode scanning
- **[Kotlin](https://kotlinlang.org/)** - Programming language
- **[Material Design 3](https://m3.material.io/)** - Design system

## 📋 Supported Formats

### QR Codes
- Standard QR codes
- WiFi network configurations
- URLs and websites
- Plain text content

### Barcodes
- **EAN-13** - International Article Number
- **EAN-8** - Short version of EAN-13
- **UPC-A** - Universal Product Code
- **UPC-E** - Short version of UPC-A
- **Code 39** - Alphanumeric barcodes
- **Code 128** - High-density barcodes
- **ITF** - Interleaved 2 of 5
- **Data Matrix** - 2D barcode
- **PDF417** - 2D barcode
- **Aztec** - 2D barcode

## 🎯 How to Use

1. **Grant Camera Permission** - Allow the app to access your camera
2. **Position the Code** - Align the QR code or barcode within the scanning frame
3. **Auto Detection** - The app automatically detects and scans codes
4. **View Results** - See the scanned content with appropriate actions
5. **Use Controls** - Switch cameras, toggle flashlight, or zoom in/out

### Controls
- **🔄 Camera Switch** - Toggle between front and rear cameras
- **💡 Flashlight** - Toggle flashlight on/off (rear camera only)
- **🖼️ Gallery** - Scan codes from saved images
- **🔍 Zoom** - Pinch to zoom for better accuracy
- **⚙️ Settings** - Access app settings and information