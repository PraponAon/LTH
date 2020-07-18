package org.ecosoft.model;

import java.io.File;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

// import org.adempiere.model.POWrapper;
import org.compiere.model.MDocType;
import org.compiere.model.MInvoice;
import org.compiere.model.MPayment;
import org.compiere.model.MPaymentAllocate;
import org.compiere.model.MPeriod;
import org.compiere.model.MPriceList;
import org.compiere.model.ModelValidationEngine;
import org.compiere.model.ModelValidator;
import org.compiere.model.Query;
// import org.compiere.model.X_C_DocType;
import org.compiere.process.DocAction;
import org.compiere.process.DocOptions;
import org.compiere.process.DocumentEngine;
import org.compiere.util.DB;
import org.compiere.util.Env;
import org.compiere.util.Msg;
//import org.globalqss.model.LCO_MInvoice;

/**
 *	Model class for Billing	
 *	
 *  @author Kitti U. - Ecosoft Co., Ltd.
 *	@version $Id: MCBilling.java, v1 2010/12/31 06:53:26 ktu Exp $
 */

public class MCBilling extends X_C_Billing implements DocAction, DocOptions
{
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;

	/**	Process Message 			*/
	private String		m_processMsg = null;

	public MCBilling(Properties ctx, int C_Billing_ID,
			String trxName) {
		super(ctx, C_Billing_ID, trxName);
		// TODO Auto-generated constructor stub
	}

	public MCBilling(Properties ctx, ResultSet rs, String trxName) {
		super(ctx, rs, trxName);
		// TODO Auto-generated constructor stub
	}
	
	public static MCBilling getBilling(Properties ctx,int C_Billing_ID,String trxName){
		return new MCBilling(ctx,C_Billing_ID,trxName);
	}

	/*
	 * Generate Billing Line (from invoices due)
	 */
	public int recalcBillingLines() {
		
		int noinv = 0;
		log.info("");
		try
		{
			// Delete previous records generated
			String sqldel = "DELETE FROM C_BillingLine "
						+ " WHERE C_Billing_ID = ?";
			PreparedStatement pstmtdel = DB.prepareStatement(sqldel, get_TrxName());
			pstmtdel.setInt(1, getC_Billing_ID());
			int nodel = pstmtdel.executeUpdate();
			log.config("C_BillingLine deleted="+nodel);
			pstmtdel.close();
			
			// Search invoice applicable for Billing
			String sql = "SELECT AD_Client_ID, AD_Org_ID, C_Invoice_ID, DateInvoiced, C_PaymentTerm_ID, " // 1..5
						+ "DueDate, invoiceOpen(C_Invoice_ID,C_InvoicePaySchedule_ID), C_InvoicePaySchedule_ID " // 6,7
						+ "FROM C_Invoice_V WHERE AD_Client_ID = ? AND AD_Org_ID = ? "
						+ "AND IsActive = 'Y' AND IsSoTrx = 'Y' AND DocStatus IN ('CO', 'CL') AND IsPaid = 'N' "
						+ "AND C_BPartner_ID = ? AND DueDate <= ? ";
			
			PreparedStatement pstmt = DB.prepareStatement(sql, get_TrxName());
			pstmt.setInt(1, getAD_Client_ID());
			pstmt.setInt(2, getAD_Org_ID());
			pstmt.setInt(3, getC_BPartner_ID());
			pstmt.setTimestamp(4, getDateBilled());
			ResultSet rs = pstmt.executeQuery();
			int line = 0;
			while (rs.next())
			{
				line = line + 10;
				MCBillingLine bline = new MCBillingLine(getCtx(), 0, get_TrxName());
				bline.setAD_Org_ID(rs.getInt(2));
				bline.setLine(line);
				bline.setC_Billing_ID(getC_Billing_ID());
				bline.setC_Invoice_ID(rs.getInt(3));
				bline.setDateInvoiced(rs.getTimestamp(4));
				bline.setC_PaymentTerm_ID(rs.getInt(5));
				bline.setDueDate(rs.getTimestamp(6));
				bline.setNetAmtToInvoice(rs.getBigDecimal(7));
				bline.setC_InvoicePaySchedule_ID(rs.getInt(8));
				bline.save();
				noinv++;
			}
			rs.close();
			pstmt.close();
		}
		catch (SQLException e)
		{
			log.log(Level.SEVERE, "", e);
			return -1;
		}

		return noinv;
	}
	
	/*
	 * Create Payment Allocation Line from Billing
	 */
	public int createLineFromBilling(MPayment pay) {
		
		int noAllocate = 0;
		log.info("");
		try
		{
			// Delete previous records generated
			String sqldel = "DELETE FROM C_PaymentAllocate "
				+ " WHERE C_Payment_ID = ?";
			PreparedStatement pstmtdel = DB.prepareStatement(sqldel, get_TrxName());
			pstmtdel.setInt(1, pay.getC_Payment_ID());
			int nodel = pstmtdel.executeUpdate();
			log.config("C_PaymentAllocate deleted="+nodel);
			pstmtdel.close();
			
			// Get invoice lines from Billing
			String sql = "SELECT Line, C_BillingLine_ID, C_Invoice_ID, "
						+ "invoiceOpen(C_Invoice_ID, C_InvoicePaySchedule_ID), "
						+ "invoiceDiscount(C_Invoice_ID, ?, C_InvoicePaySchedule_ID), "
						+ "C_InvoicePaySchedule_ID "
						+ "FROM C_BillingLine "
						+ "WHERE C_Billing_ID = ? "
						+ "AND IsActive = 'Y' "
						+ "ORDER BY Line";
			
			PreparedStatement pstmt = DB.prepareStatement(sql, get_TrxName());
			pstmt.setTimestamp(1, pay.getDateTrx());
			pstmt.setInt(2, this.getC_Billing_ID());
			ResultSet rs = pstmt.executeQuery();
			while (rs.next())
			{
				int C_BillingLine_ID = rs.getInt(2);
				int C_InvoicePaySchedule_ID = rs.getInt(6);
				int C_Invoice_ID = rs.getInt(3);
				
				BigDecimal InvoiceAmt = rs.getBigDecimal(4);
				BigDecimal DiscountAmt = rs.getBigDecimal(5);	
				BigDecimal WithholdingAmt = Env.ZERO;
				BigDecimal TaxAmt = Env.ZERO;
				BigDecimal WriteOffAmt = Env.ZERO;
				BigDecimal OverUnderAmt = Env.ZERO;
				BigDecimal Amount = Env.ZERO;
				
				BigDecimal TotalTax = Env.ZERO;
				BigDecimal PercentOpen = Env.ZERO;
				BigDecimal BaseWithholdingAmt = Env.ZERO;
				
				// == Fill Taxes (Same as LCO_CallOutWithholding.fillTaxes()) ==
				int pricelist_id = DB.getSQLValue(null, 
						"SELECT M_PriceList_ID FROM C_Invoice WHERE C_Invoice_ID=?", 
						C_Invoice_ID);
				int stdPrecision = MPriceList.getStandardPrecision(getCtx(), pricelist_id);				
				
				// Start case with WHT (can be removed if deploy with standard AD)
				/*
				try {
					// Get Invoice Open
					PercentOpen = LCO_MInvoice.getPercentInvoiceOpenAmt(C_Invoice_ID, C_InvoicePaySchedule_ID);
					// Calculate for Base Withholding
					BaseWithholdingAmt = LCO_MInvoice.getInvoiceBaseWithholding(C_Invoice_ID);
					// Get Total Invoice Tax
					TotalTax = LCO_MInvoice.getInvoiceBaseTax(C_Invoice_ID);
					
				} catch (SQLException e) {
					log.log (Level.SEVERE, "", e);
					return -1;
				}

				WithholdingAmt = BaseWithholdingAmt.multiply(PercentOpen);
				TaxAmt = TotalTax.multiply(PercentOpen);
				*/
				// End Case with WHT
				
				// == amount_allocate (Same as LCO_CallOutWithholding.amount_allocate()) ==
				Amount = InvoiceAmt.subtract(DiscountAmt).subtract(WriteOffAmt).subtract(OverUnderAmt).subtract(WithholdingAmt);

				// Create object and save it.
				MPaymentAllocate pal = new MPaymentAllocate(getCtx(), 0, get_TrxName());
				
				pal.setAD_Org_ID(pay.getAD_Org_ID());
				pal.setC_Invoice_ID(C_Invoice_ID);
				pal.setC_Payment_ID(pay.getC_Payment_ID());
				pal.set_ValueOfColumn("C_BillingLine_ID", C_BillingLine_ID);
				pal.setInvoiceAmt(InvoiceAmt.setScale(stdPrecision, BigDecimal.ROUND_HALF_UP));
				pal.setDiscountAmt(DiscountAmt.setScale(stdPrecision, BigDecimal.ROUND_HALF_UP));
				pal.set_ValueOfColumn("WithholdingAmt", WithholdingAmt.setScale(stdPrecision, BigDecimal.ROUND_HALF_UP));
				pal.set_ValueOfColumn("TaxAmt", TaxAmt.setScale(stdPrecision, BigDecimal.ROUND_HALF_UP));
				pal.setAmount(Amount.setScale(stdPrecision, BigDecimal.ROUND_HALF_UP));
				pal.setOverUnderAmt(OverUnderAmt.setScale(stdPrecision, BigDecimal.ROUND_HALF_UP));
				pal.setWriteOffAmt(WriteOffAmt.setScale(stdPrecision, BigDecimal.ROUND_HALF_UP));
				
				pal.save(get_TrxName());
				noAllocate++;
			}
			rs.close();
			pstmt.close();
			
			// Update Billing ID to Payment Window
			pay.set_ValueOfColumn("C_Billing_ID", this.getC_Billing_ID());
			pay.save(get_TrxName());
		}
		catch (SQLException e)
		{
			log.log(Level.SEVERE, "", e);
			return -1;
		}

		return noAllocate;
	}
	
	@Override
	protected boolean beforeDelete() {
		
		try
		{
			// Delete previous records generated
			String sqldel = "DELETE FROM C_BillingLine "
				+ " WHERE C_Billing_ID = ?";
			PreparedStatement pstmtdel = DB.prepareStatement(sqldel, get_TrxName());
			pstmtdel.setInt(1, getC_Billing_ID());
			int nodel = pstmtdel.executeUpdate();
			log.config("C_BillingLine deleted="+nodel);
			pstmtdel.close();
		}
		catch (SQLException e)
		{
			log.log(Level.SEVERE, "", e);
			return false;
		}
		
		return true;
	}
	
	@Override
	protected boolean afterDelete(boolean success) {
		//updateHeaderAmount();
		return true;
	}
	
	@Override
	protected boolean afterSave(boolean newRecord, boolean success) {
		//updateHeaderAmount();
		return true;
	}

	@Override
	protected boolean beforeSave(boolean newRecord) {
		// Check LineNet Amount Should Not Over Invoice Total Amount
		
		return true;
	}
	
	public boolean approveIt() {
		log.info(toString());
		//setIsApproved(true);
		return true;
	}

	@Override
	public boolean closeIt() {
		log.info(toString());
		// Before Close
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_CLOSE);
		if (m_processMsg != null)
			return false;
		// After Close
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_CLOSE);
		if (m_processMsg != null)
			return false;		
		setDocAction(DOCACTION_None);
		return true;
	}

	@Override
	public String completeIt() {
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_COMPLETE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;
		log.info(toString());
		
//		Re-Check
		if (!m_justPrepared)
		{
			String status = prepareIt();
			if (!DocAction.STATUS_InProgress.equals(status))
				return status;
		}
		
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_COMPLETE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;
		
		setProcessed(true);	
		//m_processMsg = info.toString();
		//
		setDocAction(DOCACTION_Close);
		return DocAction.STATUS_Completed;
	}

	/**
	 * 	Set Processed.
	 * 	Propagate to Lines
	 *	@param processed processed
	 */
	public void setProcessed (boolean processed)
	{
		super.setProcessed (processed);
		if (get_ID() == 0)
			return;
		String set = "SET Processed='"
			+ (processed ? "Y" : "N")
			+ "' WHERE C_Billing_ID=" + getC_Billing_ID();
		int noLine = DB.executeUpdateEx("UPDATE C_BillingLine " + set, get_TrxName());
		log.fine("setProcessed - " + processed + " - Lines=" + noLine );
	}	//	setProcessed

	@Override
	public File createPDF() {
		/*try
		{
			File temp = File.createTempFile(get_TableName()+get_ID()+"_", ".pdf");
			return createPDF (temp);
		}
		catch (Exception e)
		{
			log.severe("Could not create PDF - " + e.getMessage());
		}*/
		return null;
	}

	@Override
	public BigDecimal getApprovalAmt() {
		// TODO Auto-generated method stub
		return getGrandTotal();
	}

	@Override
	public int getDoc_User_ID() {
		// TODO Auto-generated method stub
		return getCreatedBy();
	}

	@Override
	public String getDocumentInfo() {
		MDocType dt = MDocType.get(getCtx(), getC_DocType_ID());
		return dt.getName() + " " + getDocumentNo();
	}

	@Override
	public String getProcessMsg() {
		// TODO Auto-generated method stub
		return m_processMsg;
	}

	@Override
	public String getSummary() {
		StringBuffer sb = new StringBuffer();
		sb.append(getDocumentNo());
		//	: Total Lines = 123.00 (#1)
		sb.append(": ")
			.append(Msg.translate(getCtx(),"GrandTotal")).append("=").append(this.getGrandTotal());
		//	 - Description
		if (getDescription() != null && getDescription().length() > 0)
			sb.append(" - ").append(getDescription());
		return sb.toString();
	}

	@Override
	public boolean invalidateIt() {
		log.info(toString());
		setDocAction(DOCACTION_Prepare);
		return true;
	}

	@Override
	public String prepareIt() {
		log.info(toString());
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_BEFORE_PREPARE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;
				
		if (!MPeriod.isOpen(getCtx(), getDateBilled(), 
				org.ecosoft.model.I_C_DocType.DOCBASETYPE_Billing , getAD_Org_ID()))
		{
			m_processMsg = "@PeriodClosed@";
			return DocAction.STATUS_Invalid;
		}
		
		// Should Have Line
		MCBillingLine[] bLines = getLines(false);
		if(bLines.length <= 0 ){
			m_processMsg = "@NoLines@";
			return DocAction.STATUS_Invalid;
		}

		// Verify Invoice And Find Total Amount In Line
		BigDecimal total = Env.ZERO;
		for(MCBillingLine bLine : bLines ){
			MInvoice invoice = MInvoice.get(p_ctx, bLine.getC_Invoice_ID()); 
			if(invoice != null && bLine.isActive()){
				// Check Invoice Should Be Selling Process
				if(!invoice.isSOTrx()){
					m_processMsg = "@BillInconsistencyInvoice@";
					return DocAction.STATUS_Invalid;
				}
				
				// == Compare the Open Amount with Billing Amount ==
				BigDecimal invoiceOpenAmt = invoice.getOpenAmt();
				BigDecimal invNetAmt = bLine.getNetAmtToInvoice();
				
				String sql = "SELECT SUM(bal.NetAmtToInvoice) "
						+ "FROM C_BillingLine bal JOIN C_Billing ba "
						+ "ON ba.C_Billing_ID = bal.C_Billing_ID "
						+ "AND ba.IsActive = 'Y' AND bal.IsActive = 'Y' "
						+ "WHERE bal.C_Invoice_ID = ? "
						+ "AND ba.DocStatus in ('CO'); "; // For Billed (but not closed)
				
				BigDecimal invoiceBillAmt = DB.getSQLValueBD(get_TrxName(), sql, invoice.getC_Invoice_ID());
				if(invoiceBillAmt == null) 
					invoiceBillAmt = Env.ZERO;
				
				invoiceBillAmt = invoiceBillAmt.add(invNetAmt);
					
				if(invoiceOpenAmt.compareTo(invoiceBillAmt)<0){
					m_processMsg = "@BillAmtOverInvoiceAmt@";
					return DocAction.STATUS_Invalid;
				}
			}
			
			total = total.add(bLine.getNetAmtToInvoice());
		}
		
		if(total.compareTo(getGrandTotal()) != 0){
			m_processMsg = "@BillSumInconsistent@";
			return DocAction.STATUS_Invalid;
		}
		
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this, ModelValidator.TIMING_AFTER_PREPARE);
		if (m_processMsg != null)
			return DocAction.STATUS_Invalid;
		
		m_justPrepared = true;
		if (!DOCACTION_Complete.equals(getDocAction()))
			setDocAction(DOCACTION_Complete);
		return DocAction.STATUS_InProgress;
	}
	
	/**	Just Prepared Flag			*/
	private boolean		m_justPrepared = false;
	
	MCBillingLine[] lines = null;
	
	public MCBillingLine[] getLines(boolean query){
		if(lines == null || query)
			lines = getLines(null);
		
		return lines;
	}
	
	
	public MCBillingLine[] getLines(String whereClause){
		String whereClauseFinal = "C_Billing_ID=? ";
		if (whereClause != null)
			whereClauseFinal += whereClause;
		
		List<MCBillingLine> list = new Query(getCtx(), MCBillingLine.Table_Name, whereClauseFinal, get_TrxName())
											.setParameters(new Object[]{getC_Billing_ID()})
											.setOrderBy(MCBillingLine.COLUMNNAME_Line)
											.list();
		
		return list.toArray(new MCBillingLine[list.size()]);
	}

	@Override
	public boolean processIt(String processAction) throws Exception {
		m_processMsg = null;
		DocumentEngine engine = new DocumentEngine (this, getDocStatus());
		return engine.processIt (processAction, getDocAction());
	}
	
	@Override
	public boolean reActivateIt() {
		
		log.info(toString());
		// Before reActivate
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_REACTIVATE);
		if (m_processMsg != null)
			return false;	

		// Only billing document that has not been associated with any payment can be reactivated.
		String sql = "SELECT COUNT(*) "
				+ "FROM C_Payment "
				+ "WHERE IsActive = 'Y' AND C_Billing_ID = ? ";
		
		BigDecimal i = DB.getSQLValueBD(get_TrxName(), sql, getC_Billing_ID());
		if(i == null) 
			i = Env.ZERO;
				
		if(!i.equals(Env.ZERO)){
			m_processMsg = "@BillDocUsedByPaymentDoc@";
			return false;
		}		
		
		// After reActivate
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_REACTIVATE);
		if (m_processMsg != null)
			return false;			
		
		setProcessed(false);
		setDocAction(DOCACTION_Complete);
		
		return true;
	}

	@Override
	public boolean rejectIt() {
		log.info(toString());
		//setIsApproved(false);
		return true;
	}

	@Override
	public boolean reverseAccrualIt() {
		log.info(toString());
		// Before reverseAccrual
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_REVERSEACCRUAL);
		if (m_processMsg != null)
			return false;

		// After reverseAccrual
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_REVERSEACCRUAL);
		if (m_processMsg != null)
			return false;

		return false;
	}

	@Override
	public boolean reverseCorrectIt() {
		log.info(toString());
		// Before reverseCorrect
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_REVERSECORRECT);
		if (m_processMsg != null)
			return false;
		
		// After reverseCorrect
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_REVERSECORRECT);
		if (m_processMsg != null)
			return false;
		
		return voidIt();
	}

	@Override
	public boolean unlockIt() {
		log.info("unlockIt - " + toString());
		setProcessed(false);
		return true;
	}

	@Override
	public boolean voidIt() {
		log.info(toString());
		// Before Void
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_BEFORE_VOID);
		if (m_processMsg != null)
			return false;
		
		// Only for non-Closed billing
		if (!getDocStatus().equals(DocumentEngine.STATUS_Closed))
		{
			// If billing is associated with some payment, it can't be voided.
			String sql = "SELECT COUNT(*) "
				+ "FROM C_Payment "
				+ "WHERE IsActive = 'Y' AND C_Billing_ID = ? ";
		
			BigDecimal i = DB.getSQLValueBD(get_TrxName(), sql, getC_Billing_ID());
			if(i == null) 
				i = Env.ZERO;
					
			if(!i.equals(Env.ZERO)){
				m_processMsg = "@BillDocUsedByPaymentDoc@";
				return false;
			}
		}
		
		// Set up Description as voided
		MCBillingLine[] blines = getLines(false);
		if(blines.length > 0){
			for(MCBillingLine bline : blines){
				bline.setDescription((bline.getDescription() == null ? "" : bline.getDescription())
									+ " Voided (Old Amount : "+bline.getNetAmtToInvoice()+")");
				bline.save(get_TrxName());
			}
		}
		addDescription(Msg.getMsg(getCtx(), "Voided"));
		
		// After Void
		m_processMsg = ModelValidationEngine.get().fireDocValidate(this,ModelValidator.TIMING_AFTER_VOID);
		if (m_processMsg != null)
			return false;
		
		setProcessed(true);
		setDocAction(DOCACTION_None);
		
		return true;
	}
	
	/**
	 * 	Add to Description
	 *	@param description text
	 */
	public void addDescription (String description)
	{
		String desc = getDescription();
		if (desc == null)
			setDescription(description);
		else
			setDescription(desc + " | " + description);
	}	//	addDescription

	
	public int getC_Currency_ID() {
		// TODO Auto-generated method stub
		return 0;
	}
	
    /**
     * Customize Valid Actions
     * @param docStatus
     * @param processing
     * @param orderType
     * @param isSOTrx
     * @param AD_Table_ID
     * @param docAction
     * @param options
     * @return Number of valid options
     */
    public int customizeValidActions (String docStatus, Object processing, 
                    String orderType, String isSOTrx, int AD_Table_ID, String[] docAction, String[] options, int index) {
    	
    	// If status = Drafted, add "Prepare" in the list
    	if (docStatus.equals(DocumentEngine.STATUS_Drafted)
    			|| docStatus.equals(DocumentEngine.STATUS_Invalid)) {
    		options[index++] = DocumentEngine.ACTION_Prepare;
    	}
    	
    	// If status = Completed, add "Reactivte" in the list
    	if (docStatus.equals(DocumentEngine.STATUS_Completed)) {
    		options[index++] = DocumentEngine.ACTION_ReActivate;
    		options[index++] = DocumentEngine.ACTION_Void;
    	}   	
    		
    	return index;
    }

}