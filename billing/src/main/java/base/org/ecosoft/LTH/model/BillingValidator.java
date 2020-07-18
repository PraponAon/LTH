/******************************************************************************
 * Product: Adempiere ERP & CRM Smart Business Solution                       *
 * Copyright (C) 1999-2006 ComPiere, Inc. All Rights Reserved.                *
 * This program is free software; you can redistribute it and/or modify it    *
 * under the terms version 2 of the GNU General Public License as published   *
 * by the Free Software Foundation. This program is distributed in the hope   *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.           *
 * See the GNU General Public License for more details.                       *
 * You should have received a copy of the GNU General Public License along    *
 * with this program; if not, write to the Free Software Foundation, Inc.,    *
 * 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA.                     *
 * For the text or an alternative of this public license, you may reach us    *
 * ComPiere, Inc., 2620 Augustine Dr. #245, Santa Clara, CA 95054, USA        *
 * or via info@compiere.org or http://www.compiere.org/license.html           *
 *****************************************************************************/
package org.ecosoft.model;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.logging.Level;

import org.compiere.model.MClient;
import org.compiere.model.MPayment;
import org.compiere.model.ModelValidationEngine;
import org.compiere.model.ModelValidator;
import org.compiere.model.PO;
import org.compiere.process.DocumentEngine;
import org.compiere.util.CLogger;
import org.compiere.util.DB;

/**
 *	Validator for Billing Enhancement (Thai Localisation)
 *	
 *  @author Kitti U. - Ecosoft Co., Ltd.
 *	@version $Id: LCO_Validator.java, v1 2010/12/31 06:53:26 ktu Exp $
 */
public class BillingValidator implements ModelValidator
{
	/**
	 *	Constructor.
	 *	The class is instantiated when logging in and client is selected/known
	 */
	public BillingValidator ()
	{
		super ();
	}	//	MyValidator
	
	/**	Logger			*/
	private static CLogger log = CLogger.getCLogger(BillingValidator.class);
	/** Client			*/
	private int		m_AD_Client_ID = -1;
	
	/**
	 *	Initialize Validation
	 *	@param engine validation engine 
	 *	@param client client
	 */
	public void initialize (ModelValidationEngine engine, MClient client)
	{
		if (client != null) {	
			m_AD_Client_ID = client.getAD_Client_ID();
			log.info(client.toString());
		}
		else  {
			log.info("Initializing global validator: "+this.toString());
		}

		// Tables to be monitored (n/a)
		
		// Documents to be monitored
		engine.addDocValidate(MPayment.Table_Name, this);

	}	//	initialize

	   /**
     *	Model Change of a monitored Table.
     *	Called after PO.beforeSave/PO.beforeDelete
     *	when you called addModelChange for the table
     *	@param po persistent object
     *	@param type TYPE_
     *	@return error message or null
     *	@exception Exception if the recipient wishes the change to be not accept.
     */
	public String modelChange (PO po, int type) throws Exception
	{
		log.info(po.get_TableName() + " Type: "+type);
				
		return null;
	}	//	modelChange
	
	/**
	 *	Validate Document.
	 *	Called as first step of DocAction.prepareIt 
     *	when you called addDocValidate for the table.
     *	Note that totals, etc. may not be correct.
	 *	@param po persistent object
	 *	@param timing see TIMING_ constants
     *	@return error message or null
	 */
	public String docValidate (PO po, int timing)
	{
		log.info(po.get_TableName() + " Timing: "+timing);
		String msg;
		
		// After Complete the Payment (receipt) Document, update the Billing Document associate with it
		if (po.get_TableName().equals(MPayment.Table_Name) && timing == TIMING_AFTER_COMPLETE) {
			msg = updateStatusBilling((MPayment) po);
			if (msg != null)
				return msg;
		}

		// After Payment is VOID, also void the associated Billing Document
		if (po.get_TableName().equals(MPayment.Table_Name)
				&& (timing == TIMING_AFTER_VOID || 
					timing == TIMING_AFTER_REVERSECORRECT)) {
			msg = voidBilling((MPayment) po);
			if (msg != null)
				return msg;
		}
		
		return null;
	}	//	docValidate

	private String updateStatusBilling(MPayment pay) {
		
		// Get Payment.Billing_ID
		int bill_id = pay.get_ValueAsInt("C_Billing_ID");
		if (bill_id == 0)
			return null;
		
		MCBilling bil = new MCBilling(pay.getCtx(), bill_id, pay.get_TrxName());
		if (bil == null)
			return "Not valid C_Billing_ID";
		

		// Update Cheque Received Date
		bil.setDateChequeReceived(pay.getDateTrx());
		
		// Update Billing Lines - Paid Amount and Payment ID
		if (bill_id > 0) {
			String sql = 
				"SELECT C_BillingLine_ID, Amount " +
				"FROM C_PaymentAllocate " +
				"WHERE C_Payment_ID = ? AND " +
				"IsActive = 'Y' ";
			PreparedStatement pstmt = DB.prepareStatement(sql, pay.get_TrxName());
			ResultSet rs = null;
			try {
				pstmt.setInt(1, pay.getC_Payment_ID());
				rs = pstmt.executeQuery();
				while (rs.next()) {
					int bline_id = rs.getInt(1);
					if (bline_id > 0)
					{
						MCBillingLine bline = new MCBillingLine(
								pay.getCtx(), rs.getInt(1), pay.get_TrxName());
						bline.setC_Payment_ID(pay.getC_Payment_ID());
						bline.setPaidAmt(rs.getBigDecimal(2));
						if (!bline.save(pay.get_TrxName()))
							return "Error updating Billing Lines";
					}
				}
			} catch (SQLException e) {
				log.log(Level.SEVERE, sql, e);
				return e.getLocalizedMessage();
			} finally {
				DB.close(rs, pstmt);
				rs = null; pstmt = null;
			}
		}
		
		// Close Billing Document
		if (!bil.closeIt())
			return "Can not close associated Billing Document";
		bil.setDocStatus(DocumentEngine.STATUS_Closed);
		bil.save(pay.get_TrxName());
		
		return null;
	}

	private String voidBilling(MPayment pay) {
		
		// Get Payment.Billing_ID
		int bill_id = pay.get_ValueAsInt("C_Billing_ID");
		if (bill_id == 0)
			return null;
		
		MCBilling bil = new MCBilling(pay.getCtx(), bill_id, pay.get_TrxName());
		if (bil == null)
			return "Not valid C_Billing_ID";
				
		// Void Billing Document, only if it has been closed previously
		// If not close, it can be used for other payment, no need to void.
		if (bil.getDocStatus().equals(DocumentEngine.STATUS_Closed)) {
				if (!bil.voidIt())
					return "Can not void associated Billing Document";
				bil.setDocStatus(DocumentEngine.STATUS_Voided);
				bil.save(pay.get_TrxName());
		}
		
		return null;
	}
	
	/**
	 *	User Login.
	 *	Called when preferences are set
	 *	@param AD_Org_ID org
	 *	@param AD_Role_ID role
	 *	@param AD_User_ID user
	 *	@return error message or null
	 */
	public String login (int AD_Org_ID, int AD_Role_ID, int AD_User_ID)
	{
		log.info("AD_User_ID=" + AD_User_ID);
		return null;
	}	//	login

	
	/**
	 *	Get Client to be monitored
	 *	@return AD_Client_ID client
	 */
	public int getAD_Client_ID()
	{
		return m_AD_Client_ID;
	}	//	getAD_Client_ID

	
	/**
	 * 	String Representation
	 *	@return info
	 */
	public String toString ()
	{
		StringBuffer sb = new StringBuffer ("BillingValidator");
		return sb.toString ();
	}	//	toString
	
	
}	//	BillingValidator