# Política de Seguridad (Security Policy)

## 1. Versiones Compatibles y Mantenidas

El proyecto **El Canario Chillador** implementa un enfoque DevSecOps con auditoría automatizada en cada integración continua (Gitleaks, Semgrep, Trivy, Flawfinder y OWASP Dependency-Check).

| Componente | Versión | Estado de Soporte |
| :--- | :--- | :--- |
| **Firmware ESP32** | `v1.0.x` | :white_check_mark: Soportado |
| **Aplicación Android** | `v1.0.x` | :white_check_mark: Soportado |
| **Aplicación Desktop (Windows)** | `v1.0.x` | :white_check_mark: Soportado |

---

## 2. Reporte Responsable de Vulnerabilidades

La seguridad de este sistema de alerta de gases es prioritaria, ya que está diseñado para la protección de vidas humanas en atmósferas con riesgo químico y de deflagración.

Si descubres una posible vulnerabilidad de seguridad, fallo en el aislamiento de procesos o riesgo en las comunicaciones Bluetooth/Serial:

1. **No abras un Issue público** para describir la falla antes de que pueda ser mitigada.
2. Abre un **Security Advisory Privado** directamente en la pestaña [Security > Advisories](https://github.com/SaxorRust/ProyectoCanarioChillador/security/advisories) del repositorio en GitHub.
3. Incluye:
   - Descripción detallada del hallazgo.
   - Pasos para reproducir el comportamiento o prueba de concepto (PoC).
   - Módulo afectado (Firmware ESP32, App Android, App Desktop o Pipeline CI).

---

## 3. Prácticas DevSecOps Implementadas

* **Detección de Secretos:** Escaneo automático con **Gitleaks** para impedir fuga de credenciales o certificados en el historial de Git.
* **Análisis Estático (SAST):** Reglas OWASP Top 10 aplicadas con **Semgrep** en Java/C++ y análisis estático con **Flawfinder** y **Cppcheck**.
* **Gestión de Componentes (SCA):** Análisis de vulnerabilidades y licencias con **Aqua Security Trivy** y **OWASP Dependency-Check**.
* **Protección de Datos Locales:** Uso estricto de SQLite parametrizado (anti SQL Injection) y aislamiento de archivos compartidos mediante `CanarioFileProvider` nativo.
