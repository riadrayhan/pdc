from fastapi import APIRouter, Depends, HTTPException, status, Query
from sqlalchemy.orm import Session
from typing import Optional, List
from uuid import UUID
from datetime import date

from app.core import get_db, get_current_user, get_current_admin_user
from app.models import User, EMIContract, EMIPayment, ContractStatus, PaymentStatus
from app.schemas import (
    EMIContractCreate, EMIContractUpdate, EMIContractResponse, EMIContractListResponse,
    EMIPaymentCreate, EMIPaymentResponse, EMIPaymentListResponse, OverdueReport
)
from app.services import EMIService, DeviceService
from app.models import DeviceStatus

router = APIRouter(prefix="/emi", tags=["EMI Management"])


@router.post("/contracts", response_model=EMIContractResponse, status_code=status.HTTP_201_CREATED)
async def create_contract(
    contract_data: EMIContractCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Create a new EMI contract"""
    contract = EMIService.create_contract(db, contract_data, current_user.id)
    return contract


@router.get("/contracts", response_model=EMIContractListResponse)
async def list_contracts(
    skip: int = Query(0, ge=0),
    limit: int = Query(50, ge=1, le=100),
    status: Optional[ContractStatus] = None,
    customer_id: Optional[UUID] = None,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """List all EMI contracts"""
    query = db.query(EMIContract)
    
    if status:
        query = query.filter(EMIContract.status == status)
    if customer_id:
        query = query.filter(EMIContract.customer_id == customer_id)
    
    total = query.count()
    contracts = query.order_by(EMIContract.created_at.desc()).offset(skip).limit(limit).all()
    
    return EMIContractListResponse(total=total, contracts=contracts)


@router.get("/contracts/{contract_id}", response_model=EMIContractResponse)
async def get_contract(
    contract_id: UUID,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Get contract by ID"""
    contract = db.query(EMIContract).filter(EMIContract.id == contract_id).first()
    if not contract:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Contract not found"
        )
    return contract


@router.put("/contracts/{contract_id}", response_model=EMIContractResponse)
async def update_contract(
    contract_id: UUID,
    contract_data: EMIContractUpdate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_admin_user)
):
    """Update contract details"""
    contract = db.query(EMIContract).filter(EMIContract.id == contract_id).first()
    if not contract:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Contract not found"
        )
    
    update_data = contract_data.model_dump(exclude_unset=True)
    for field, value in update_data.items():
        setattr(contract, field, value)
    
    db.commit()
    db.refresh(contract)
    return contract


# Payment endpoints
@router.get("/contracts/{contract_id}/payments", response_model=EMIPaymentListResponse)
async def list_contract_payments(
    contract_id: UUID,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """List all payments for a contract"""
    payments = db.query(EMIPayment).filter(
        EMIPayment.contract_id == contract_id
    ).order_by(EMIPayment.installment_number).all()
    
    return EMIPaymentListResponse(total=len(payments), payments=payments)


@router.post("/payments/{payment_id}/record", response_model=EMIPaymentResponse)
async def record_payment(
    payment_id: UUID,
    payment_data: EMIPaymentCreate,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Record a payment"""
    payment = EMIService.record_payment(db, payment_id, payment_data, current_user.id)
    if not payment:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Payment not found"
        )
    
    # If payment is made, check if device should be unlocked
    if payment.status == PaymentStatus.PAID:
        contract = payment.contract
        # Check if all overdue payments are cleared
        overdue = db.query(EMIPayment).filter(
            EMIPayment.contract_id == contract.id,
            EMIPayment.due_date < date.today(),
            EMIPayment.status.in_([PaymentStatus.PENDING, PaymentStatus.PARTIAL])
        ).count()
        
        if overdue == 0:
            # Unlock device
            DeviceService.update_device_status(
                db, contract.device_id, DeviceStatus.ACTIVE,
                current_user.id, "Payment received - all dues cleared"
            )
    
    return payment


@router.get("/overdue", response_model=List[OverdueReport])
async def get_overdue_payments(
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Get all overdue payments with customer and device info"""
    payments = EMIService.get_overdue_payments(db)
    
    reports = []
    for payment in payments:
        contract = payment.contract
        customer = contract.customer
        device = db.query(__import__('app.models', fromlist=['Device']).Device).filter(
            __import__('app.models', fromlist=['Device']).Device.id == contract.device_id
        ).first()
        
        days_overdue = (date.today() - payment.due_date).days
        
        reports.append(OverdueReport(
            contract_id=contract.id,
            contract_number=contract.contract_number,
            customer_name=customer.full_name,
            customer_phone=customer.phone,
            device_imei=device.imei if device else "N/A",
            due_date=payment.due_date,
            days_overdue=days_overdue,
            amount_due=payment.due_amount - payment.paid_amount,
            device_status=device.status.value if device else "unknown"
        ))
    
    return reports


@router.get("/due-soon", response_model=List[EMIPaymentResponse])
async def get_payments_due_soon(
    days: int = Query(3, ge=1, le=30),
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_user)
):
    """Get payments due within specified days"""
    payments = EMIService.get_payments_due_soon(db, days)
    return payments


@router.delete("/contracts/{contract_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_contract(
    contract_id: UUID,
    db: Session = Depends(get_db),
    current_user: User = Depends(get_current_admin_user)
):
    """Permanently delete a contract and all its payments"""
    contract = db.query(EMIContract).filter(EMIContract.id == contract_id).first()
    if not contract:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="Contract not found"
        )
    
    # Delete all payments for this contract
    db.query(EMIPayment).filter(EMIPayment.contract_id == contract_id).delete()
    db.delete(contract)
    db.commit()
    return None
