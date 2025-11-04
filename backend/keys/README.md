# Tenant Private Keys Directory

Este directorio almacena las claves privadas RSA (.pem) de cada tenant para autenticación con Glovo API.

## Estructura

```
keys/
├── .gitignore          # Previene que archivos .pem se suban a Git
├── .gitkeep            # Mantiene el directorio en Git
├── README.md           # Este archivo
└── tenant_{id}.pem     # Claves privadas por tenant (NO SE SUBEN A GIT)
```

## ⚠️ IMPORTANTE - SEGURIDAD

- **NUNCA** subir archivos `.pem` al repositorio
- **NUNCA** compartir claves privadas por email/chat
- Los archivos `.pem` se crean automáticamente durante el onboarding del tenant
- Cada tenant tiene su propio archivo `.pem` único

## Flujo de Creación

1. Usuario completa onboarding en RiTrack
2. Usuario sube su archivo `.pem` (obtenido de Glovo Partner Portal)
3. Sistema valida el archivo y lo guarda como `tenant_{id}.pem`
4. Sistema usa este archivo para autenticación OAuth2 con Glovo API

## Formato del Archivo

Los archivos `.pem` deben ser claves privadas RSA en formato PKCS#8:

```
-----BEGIN PRIVATE KEY-----
MIIEvgIBADANBgkqhkiG9w0BAQEFAASCBKgwggSkAgEAAoIBAQC...
...
-----END PRIVATE KEY-----
```

## Gestión Manual (solo para testing)

Para agregar un tenant manualmente (desarrollo/testing):

```bash
# 1. Copiar el archivo .pem del tenant
cp /ruta/al/archivo.pem ./keys/tenant_1.pem

# 2. Verificar permisos (solo lectura para la aplicación)
chmod 600 ./keys/tenant_1.pem
```

## Backup

**CRÍTICO:** Hacer backup seguro de estos archivos en producción:

- Usar secretos de Kubernetes
- Usar AWS Secrets Manager / Azure Key Vault
- Usar variables de entorno cifradas
- **NO** almacenar en sistemas de archivos sin cifrar

## Limpieza

Al eliminar un tenant, recordar eliminar su archivo `.pem`:

```bash
rm ./keys/tenant_{id}.pem
```

O usar el método del servicio:
```java
fileStorageService.deletePemFile(pemPath);
```
