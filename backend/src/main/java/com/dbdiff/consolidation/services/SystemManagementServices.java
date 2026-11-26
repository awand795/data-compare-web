package com.dbdiff.consolidation.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONObject;
import org.springframework.stereotype.Service;

import com.dbdiff.consolidation.utils.NativeQuery;
import com.dbdiff.consolidation.utils.ResponseData;

@Service("consolidationSystemManagementServices")
public class SystemManagementServices {

    public Object getDataPerusahaan(JSONObject requestParam, JSONObject authParam) {
        List<Map<String, Object>> resData = new ArrayList<>();

        String qSub = null;
        String[] qSelect = { "kode_perusahaan", "nama_perusahaan", "nama_singkat", "url_erp", "akses" };
        String qFrom = "sch_konsol.mhs_perusahaan";
        String qJoin = null;
        String pass = requestParam != null ? requestParam.optString("pass", "").trim() : "";
        String qWhere;
        if (!pass.isEmpty() && !pass.equalsIgnoreCase("ALL")) {
            qWhere = "status_tampil = 'Y' AND tipe_datasource = 'P' AND akses = '" + pass.replace("'", "''") + "'";
        } else {
            qWhere = "status_tampil = 'Y' AND tipe_datasource = 'P'";
        }
        String qOrderBy = "nama_singkat, kode_perusahaan";
        Integer qLimit = null;

        try {
            resData = NativeQuery.querySelect(qSub, qSelect, qFrom, qJoin, qWhere, qOrderBy, qLimit, null);
            return ResponseData.buildResponse(200, "Y", resData);
        } catch (Exception e) {
            Logger.getLogger(SystemManagementServices.class.getName()).log(Level.SEVERE, e.getMessage(), e);
        }

        return ResponseData.buildResponse(400, "N", "GAGAL MENDAPATKAN DATA");
    }
}
