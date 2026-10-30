import os
import smtplib
from email.message import EmailMessage

def send_otp_email(to_email: str, otp: str):
    smtp_host = os.environ.get("SMTP_HOST")
    smtp_port = os.environ.get("SMTP_PORT")
    smtp_user = os.environ.get("SMTP_USER")
    smtp_pass = os.environ.get("SMTP_PASS")
    smtp_from = os.environ.get("SMTP_FROM_EMAIL", smtp_user)

    if not all([smtp_host, smtp_port, smtp_user, smtp_pass]):
        print(f"\n[DEV MODE] SMTP not configured. OTP for {to_email} is: {otp}\n")
        return

    msg = EmailMessage()
    msg.set_content(f"Welcome to CacheTune!\n\nYour verification code is: {otp}\n\nThis code will expire in 10 minutes.")
    msg["Subject"] = "CacheTune Email Verification"
    msg["From"] = smtp_from
    msg["To"] = to_email

    try:
        with smtplib.SMTP(smtp_host, int(smtp_port)) as server:
            server.starttls()
            server.login(smtp_user, smtp_pass)
            server.send_message(msg)
    except Exception as e:
        print(f"Failed to send email: {e}")
        # In production we might raise an HTTPException, but falling back to logging for safety during dev
        print(f"\n[DEV MODE FALLBACK] OTP for {to_email} is: {otp}\n")
