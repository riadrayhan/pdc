import uuid
from datetime import datetime
from sqlalchemy import Column, String, DateTime, Text
from app.core.types import UUID
from sqlalchemy.orm import relationship

from app.core.database import Base


class Customer(Base):
    __tablename__ = "customers"
    
    id = Column(UUID(), primary_key=True, default=uuid.uuid4)
    
    # Personal Information
    full_name = Column(String(255), nullable=False)
    phone = Column(String(20), nullable=False, index=True)
    alternate_phone = Column(String(20))
    email = Column(String(255))
    
    # Identity (stored as hash for security)
    id_type = Column(String(50))  # aadhaar, pan, voter_id, etc.
    id_hash = Column(String(255))  # Hashed ID number
    
    # Address
    address = Column(Text)
    city = Column(String(100))
    state = Column(String(100))
    pincode = Column(String(10))
    
    # Emergency Contact
    emergency_contact_name = Column(String(255))
    emergency_contact_phone = Column(String(20))
    emergency_contact_relation = Column(String(50))
    
    # Relations
    devices = relationship("Device", back_populates="customer")
    contracts = relationship("EMIContract", back_populates="customer")
    
    # Timestamps
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)
    
    def __repr__(self):
        return f"<Customer {self.full_name}>"
