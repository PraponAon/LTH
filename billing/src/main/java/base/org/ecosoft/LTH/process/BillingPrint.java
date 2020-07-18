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
package org.ecosoft.process;


import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.logging.Level;

import org.compiere.process.*;
import org.compiere.model.MClient;
import org.compiere.print.ServerReportCtl;
import org.compiere.print.MPrintFormat;
import org.compiere.util.AdempiereUserError;
import org.compiere.util.DB;
import org.compiere.util.Env;

/**
 *	Process Billing Print	
 *	
 *  @author Kitti U. - Ecosoft Co., Ltd.
 *	@version $Id: BillingPrint.java, v1 2010/12/31 06:53:26 ktu Exp $
 */
public class BillingPrint extends SvrProcess
{
	private Timestamp	m_dateBilled = null;
	private int			m_C_BPartner_ID = 0;
	private String		m_DocumentNo_From = null;
	private String		m_DocumentNo_To = null;
	private boolean		p_IsComplete = false;

	/**
	 *  Prepare - e.g., get Parameters.
	 */
	protected void prepare()
	{
		ProcessInfoParameter[] para = getParameter();
		for (int i = 0; i < para.length; i++)
		{
			String name = para[i].getParameterName();
			if (para[i].getParameter() == null)
				;
			else if (name.equals("DateBilled"))
			{
				m_dateBilled = ((Timestamp)para[i].getParameter());
			}		
			else if (name.equals("C_BPartner_ID"))
				m_C_BPartner_ID = para[i].getParameterAsInt();
			else if (name.equals("DocumentNo"))
			{
				m_DocumentNo_From = (String)para[i].getParameter();
				m_DocumentNo_To = (String)para[i].getParameter_To();
			}
			else if (name.equals("IsComplete"))
				p_IsComplete = "Y".equals(para[i].getParameter());	
			else
				log.log(Level.SEVERE, "prepare - Unknown Parameter: " + name);
		}
		if (m_DocumentNo_From != null && m_DocumentNo_From.length() == 0)
			m_DocumentNo_From = null;
		if (m_DocumentNo_To != null && m_DocumentNo_To.length() == 0)
			m_DocumentNo_To = null;
	}	//	prepare

	/**
	 *  Perform process.
	 *  @return Message
	 *  @throws Exception
	 */
	protected String doIt() throws java.lang.Exception
	{
		
		//	Too broad selection, at least 1 criteria must be selected
		if (m_dateBilled == null && m_DocumentNo_From == null && m_DocumentNo_To == null)
			throw new AdempiereUserError ("@RestrictSelection@");

		// Get Info
		// Payment ID, PaymentSelection ID, 
		// DocType, PrintFormat
		StringBuffer sql = new StringBuffer (
				"SELECT bil.C_Billing_ID, pf.AD_PrintFormat_ID, "
				+ "COALESCE(dt.DocumentCopies,0)+COALESCE(bp.DocumentCopies,0) as Copies, "
				+ "bil.DocumentNo, "
				+ "bp.C_BPartner_ID  "
				+ "FROM C_Billing bil "
				+ "INNER JOIN C_BPartner bp ON (bil.C_BPartner_ID=bp.C_BPartner_ID) "
				+ "INNER JOIN AD_Client c ON (bil.AD_Client_ID=c.AD_Client_ID) "
				+ "INNER JOIN C_DocType dt ON (bil.C_DocType_ID=dt.C_DocType_ID) "
				+ "INNER JOIN AD_PrintFormat pf ON (pf.AD_PrintFormat_ID = dt.AD_PrintFormat_ID)"
			    + " WHERE bil.AD_Client_ID=? AND bil.AD_Org_ID=? AND "
			    + "       pf.AD_Org_ID IN (0,bil.AD_Org_ID) " );
		
		if (m_dateBilled != null)
		{
			sql.append(" AND ");
			sql.append("TRUNC(bil.DateBilled) = ")
				.append(DB.TO_DATE(m_dateBilled, true));
		}
		
		if (m_C_BPartner_ID != 0)
		{
			sql.append(" AND ");
			sql.append ("bil.C_BPartner_ID=").append (m_C_BPartner_ID);
		}
		
		if (m_DocumentNo_From != null && m_DocumentNo_To != null)
		{
			sql.append(" AND ");
			sql.append("bil.DocumentNo BETWEEN ")
				.append(DB.TO_STRING(m_DocumentNo_From)).append(" AND ")
				.append(DB.TO_STRING(m_DocumentNo_To));
		}
		else if (m_DocumentNo_From != null)
		{
			sql.append(" AND ");
			if (m_DocumentNo_From.indexOf('%') == -1)
				sql.append("bil.DocumentNo >= ")
					.append(DB.TO_STRING(m_DocumentNo_From));
			else
				sql.append("bil.DocumentNo LIKE ")
					.append(DB.TO_STRING(m_DocumentNo_From));
		}
		
		if (p_IsComplete) 
		{
			sql.append(" AND ");
			sql.append("bil.DocStatus IN ('CO') "); 
		}
		
		sql.append(" ORDER BY bil.DocumentNo");
		log.fine(sql.toString());

		MPrintFormat format = null;
		int count = 0;
		int errors = 0;
		PreparedStatement pstmt = null;
		ResultSet rs = null;
		
		try
		{
			pstmt = DB.prepareStatement(sql.toString(), get_TrxName()); 
			pstmt.setInt(1, Env.getAD_Client_ID(Env.getCtx()));
			pstmt.setInt(2, Env.getAD_Org_ID(Env.getCtx()));
			rs = pstmt.executeQuery();
			
			while (rs.next())
			{
				int C_Billing_ID = rs.getInt(1);
				int AD_PrintFormat_ID = rs.getInt(2);
				int copies = rs.getInt(3);
				if (copies == 0)
					copies = 1;
				String DocumentNo = rs.getString(4);

				if (AD_PrintFormat_ID == 0)
				{
					addLog (C_Billing_ID, null, null, DocumentNo + " No Print Format");
					errors++;
					continue;
				}
				//	Get Format & Data
				format = MPrintFormat.get (getCtx(), AD_PrintFormat_ID, false);
				
				//boolean printed = false;
				//if (!ServerReportCtl.startDocumentPrint(0, format, C_Billing_ID, null))   // ของเดิม
				if (!ServerReportCtl.startDocumentPrint(0, format, C_Billing_ID, null, null))   // processInfo   prapon 26.10.2018, 11/01/2019					
					errors++;
				else
					count++;
				//printed = true;
			}	//	for all entries						
		}
		catch (Exception e)
		{
			log.log(Level.SEVERE, "doIt - " + sql, e);
			throw new Exception (e);
		}
		finally {
			DB.close(rs, pstmt);
		}
		//
		return "@Printed@=" + count + " - @Errors@=" + errors;
	}	//	doIt

}	//	BillingPrint
