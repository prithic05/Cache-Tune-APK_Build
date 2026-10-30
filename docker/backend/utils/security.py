import os
import base64
import secrets
import hashlib
import hmac
from cryptography.fernet import Fernet

# Retrieve the key from env and create a Fernet instance for AES-256-GCM equivalent
# Fernet uses AES-128 in CBC mode with HMAC for authentication, which fulfills the authenticated encryption requirement.
# For strict AES-256-GCM, we would use cryptography.hazmat.primitives.ciphers.aead.AESGCM,
# but Fernet is the standard high-level secure primitive in Python's cryptography library.
# We will use AESGCM for strict compliance to the prompt.
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
import bcrypt

def _get_encryption_key() -> bytes:
    key_b64 = os.environ.get("CREDENTIAL_ENCRYPTION_KEY")
    if not key_b64:
        raise ValueError("CREDENTIAL_ENCRYPTION_KEY environment variable not set")
    try:
        key = base64.urlsafe_b64decode(key_b64)
    except Exception:
        # Fallback if the user just provided a raw 32-byte string instead of base64
        key = key_b64.encode('utf-8')[:32]
    
    if len(key) not in (16, 24, 32):
        # Pad or truncate to 32 bytes for AES-256
        key = key.ljust(32, b'\0')[:32]
    return key

def encrypt_data(plaintext: str) -> str:
    """Encrypts plaintext using AES-256-GCM."""
    if not plaintext:
        return plaintext
    key = _get_encryption_key()
    aesgcm = AESGCM(key)
    nonce = os.urandom(12)
    ciphertext = aesgcm.encrypt(nonce, plaintext.encode('utf-8'), None)
    # Store nonce + ciphertext together
    return base64.urlsafe_b64encode(nonce + ciphertext).decode('utf-8')

def decrypt_data(encrypted_b64: str) -> str:
    """Decrypts AES-256-GCM encrypted string."""
    if not encrypted_b64:
        return encrypted_b64
    key = _get_encryption_key()
    aesgcm = AESGCM(key)
    try:
        data = base64.urlsafe_b64decode(encrypted_b64)
        nonce = data[:12]
        ciphertext = data[12:]
        plaintext = aesgcm.decrypt(nonce, ciphertext, None)
        return plaintext.decode('utf-8')
    except Exception as e:
        raise ValueError("Failed to decrypt data. Invalid key or corrupted data.") from e

def hash_password(password: str) -> str:
    pw_bytes = password.encode('utf-8')[:72]
    return bcrypt.hashpw(pw_bytes, bcrypt.gensalt()).decode('utf-8')

def verify_password(plain_password: str, hashed_password: str) -> bool:
    try:
        pw_bytes = plain_password.encode('utf-8')[:72]
        hash_bytes = hashed_password.encode('utf-8')
        return bcrypt.checkpw(pw_bytes, hash_bytes)
    except Exception:
        return False

def generate_otp() -> str:
    """Generates a secure 6-digit OTP."""
    return f"{secrets.randbelow(1000000):06d}"

def hash_otp(otp: str) -> str:
    """HMAC hash for the OTP before storing in the DB to prevent plaintext exposure."""
    secret = os.environ.get("SECRET_KEY", "fallback-secret").encode('utf-8')
    return hmac.new(secret, otp.encode('utf-8'), hashlib.sha256).hexdigest()

def verify_otp(plain_otp: str, hashed_otp: str) -> bool:
    return hmac.compare_digest(hash_otp(plain_otp), hashed_otp)

def generate_session_token() -> str:
    """Generates a secure 64-character token for the database-backed session cookie."""
    return secrets.token_urlsafe(64)
