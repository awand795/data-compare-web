package com.dbdiff.consolidation.services;

import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import com.dbdiff.consolidation.utils.DataSourceType;
import com.dbdiff.consolidation.utils.G;
import com.dbdiff.consolidation.utils.NativeQuery;
import com.dbdiff.consolidation.utils.ResponseData;

@Service("consolidationKonsolidasiServices")
public class KonsolidasiServices {

    public Object getDataLanggananKonsolidasi(JSONObject requestParam, JSONObject authParam) {
        List<Map<String, Object>> resCount;
        List<Map<String, Object>> resData;

        String qSub = null;
        String[] selectCountData = { "count(*) AS data_count" };
        String[] qSelect = { "nama_langganan", "alamat", "kode_kelurahan_lgn", "wilayah_deskripsi_lgn", "alamat_gudang",
                "kode_kelurahan", "wilayah_deskripsi", "contact_person", "telepon", "hp", "hp2", "email1", "email2",
                "tahun_berdiri", "npwp", "alamat_npwp", "nomor_ktp", "nama_ktp", "alamat_ktp", "latitude", "longitude",
                "altitude", "id_langganan" };
        String qFrom = "sch_konsol.mhs_langganan";
        String qJoin = null;
        String qWhere = null;
        String qOrderBy = "nama_langganan";
        Integer qLimit = 2000;
        Integer qOffset = 0;

        try {
            resCount = NativeQuery.querySelect(qSub, selectCountData, qFrom, qJoin, qWhere, null, qLimit, null);
            JSONArray convert = new JSONArray();
            for (Map<String, Object> map : resCount) {
                convert.put(new JSONObject(map));
            }
            if (!convert.isEmpty()) {
                BigDecimal page = convert.getJSONObject(0).getBigDecimal("data_count").divide(BigDecimal.valueOf(2000), 0,
                        RoundingMode.UP);
                qOffset = page.intValue();
            }
        } catch (Exception e) {
        }

        try {
            resData = NativeQuery.querySelect(qSub, qSelect, qFrom, qJoin, qWhere, qOrderBy, qLimit,
                    requestParam.getInt("page"));
            return ResponseData.buildResponse(200, "Y", resData, qOffset);
        } catch (Exception e) {
            Logger.getLogger(KonsolidasiServices.class.getName()).log(Level.SEVERE, e.getMessage(), e);
        }

        return ResponseData.buildResponse(400, "N", "GAGAL MENDAPATKAN DATA");
    }

    public Object getDataLanggananERP(JSONObject requestParam, JSONObject authParam) {
        List<Map<String, Object>> resData = new ArrayList<>();
        JSONArray resERP = new JSONArray();
        String resCodeERP = "dk-data-langganan";
        JSONObject reqParamERP = new JSONObject();
        JSONObject payloadERP = new JSONObject();

        payloadERP.put("responseCode", resCodeERP);
        payloadERP.put("requestParam", reqParamERP);
        payloadERP.put("authParam", authParam);

        String endpoint = requestParam.getString("api_method").equalsIgnoreCase("standart-api") ? "postdata"
                : requestParam.getString("api_method").equalsIgnoreCase("custom-api") ? "postquery" : "";
        String urlERP = requestParam.getString("url_erp") + "erp-ws-main/ws/" + endpoint;
        try {
            resERP = DataSourceType.dataSourceERPExtern(urlERP, payloadERP);
        } catch (UnsupportedEncodingException e) {
        }

        if (!resERP.isEmpty()) {
            for (int i = 0; i < resERP.length(); i++) {
                JSONObject tmpData = new JSONObject(resERP.getJSONObject(i).toMap());

                Map<String, Object> initData = new HashMap<>();
                initData.put("nama_langganan",
                        tmpData.has("nama_langganan") ? tmpData.getString("nama_langganan") : "");
                initData.put("alamat", tmpData.has("alamat") ? tmpData.getString("alamat") : "");
                initData.put("kode_kelurahan_lgn",
                        tmpData.has("kode_kelurahan_lgn") ? tmpData.getString("kode_kelurahan_lgn") : "");
                initData.put("wilayah_deskripsi_lgn",
                        tmpData.has("wilayah_deskripsi_lgn") ? tmpData.getString("wilayah_deskripsi_lgn") : "");
                initData.put("alamat_gudang", tmpData.has("alamat_gudang") ? tmpData.getString("alamat_gudang") : "");
                initData.put("kode_kelurahan",
                        tmpData.has("kode_kelurahan") ? tmpData.getString("kode_kelurahan") : "");
                initData.put("wilayah_deskripsi",
                        tmpData.has("wilayah_deskripsi") ? tmpData.getString("wilayah_deskripsi") : "");
                initData.put("contact_person",
                        tmpData.has("contact_person") ? tmpData.getString("contact_person") : "");
                initData.put("telepon", tmpData.has("telepon") ? tmpData.getString("telepon") : "");
                initData.put("hp", tmpData.has("hp") ? tmpData.getString("hp") : "");
                initData.put("hp2", tmpData.has("hp2") ? tmpData.getString("hp2") : "");
                initData.put("email1", tmpData.has("email1") ? tmpData.getString("email1") : "");
                initData.put("email2", tmpData.has("email2") ? tmpData.getString("email2") : "");
                initData.put("tahun_berdiri",
                        tmpData.has("tahun_berdiri") ? BigDecimal.valueOf(tmpData.getDouble("tahun_berdiri"))
                                : BigDecimal.ZERO);
                initData.put("npwp", tmpData.has("npwp") ? tmpData.getString("npwp") : "00.000.000.0-000.000");
                initData.put("alamat_npwp", tmpData.has("alamat_npwp") ? tmpData.getString("alamat_npwp") : "");
                initData.put("nomor_ktp", tmpData.has("nomor_ktp") ? tmpData.getString("nomor_ktp") : "");
                initData.put("nama_ktp", tmpData.has("nama_ktp") ? tmpData.getString("nama_ktp") : "");
                initData.put("alamat_ktp", tmpData.has("alamat_ktp") ? tmpData.getString("alamat_ktp") : "");
                initData.put("latitude",
                        tmpData.has("latitude") ? BigDecimal.valueOf(tmpData.getDouble("latitude")) : BigDecimal.ZERO);
                initData.put("longitude",
                        tmpData.has("longitude") ? BigDecimal.valueOf(tmpData.getDouble("longitude")) : BigDecimal.ZERO);
                initData.put("altitude",
                        tmpData.has("altitude") ? BigDecimal.valueOf(tmpData.getDouble("altitude")) : BigDecimal.ZERO);
                initData.put("kode_langganan",
                        tmpData.has("kode_langganan") ? tmpData.getString("kode_langganan") : "");

                resData.add(initData);
            }
            return ResponseData.buildResponse(200, "Y", resData);
        }

        return ResponseData.buildResponse(400, "N", "GAGAL MENDAPATKAN DATA");
    }

    public Object getDataLanggananPerusahaan(JSONObject requestParam, JSONObject authParam) {
        List<Map<String, Object>> resData;

        String qSub = null;
        String[] qSelect = null;
        String qFrom = "sch_konsol.view_langganan_perusahaan";
        String qJoin = null;
        String kodePerusahaan = requestParam.optString("kode_perusahaan", "").replace("'", "''");
        String qWhere = "kode_perusahaan = '" + kodePerusahaan + "'";
        String qOrderBy = "nama_langganan, kode_perusahaan";
        Integer qLimit = null;

        try {
            resData = NativeQuery.querySelect(qSub, qSelect, qFrom, qJoin, qWhere, qOrderBy, qLimit, null);
            return ResponseData.buildResponse(200, "Y", resData);
        } catch (Exception e) {
            Logger.getLogger(KonsolidasiServices.class.getName()).log(Level.SEVERE, e.getMessage(), e);
        }

        return ResponseData.buildResponse(400, "N", "GAGAL MENDAPATKAN DATA");
    }

    public Object setDataLanggananERPNew(JSONObject requestParam, JSONObject authParam) {
        String idLangganan, funcName = "sch_konsol.insert_data_langganan";

        String resCodeERP = "SET-MS-LANGGANAN-RELASI";
        JSONObject reqParamERP = new JSONObject();
        JSONObject payloadERP = new JSONObject();
        JSONObject resERP = new JSONObject();

        try {
            String resDataKonsolidasi = NativeQuery.queryFunctionDBString(funcName, requestParam);
            JSONObject initData = new JSONObject(resDataKonsolidasi);
            idLangganan = initData.get("ID_LANGGANAN").toString();

            if (!idLangganan.equalsIgnoreCase("")) {
                if (requestParam.has("kode_langganan")
                        && !requestParam.getString("kode_langganan").equalsIgnoreCase("")) {
                    reqParamERP.put("KODE_RELASI", idLangganan);
                    reqParamERP.put("PROSES_KONSOL", "N");
                    reqParamERP.put("KODE_LANGGANAN", requestParam.getString("kode_langganan"));

                    payloadERP.put("responseCode", resCodeERP);
                    payloadERP.put("requestParam", reqParamERP);
                    payloadERP.put("authParam", authParam);

                    String endpoint = requestParam.getString("api_method").equalsIgnoreCase("standart-api") ? "postdata"
                            : requestParam.getString("api_method").equalsIgnoreCase("custom-api") ? "postquery" : "";
                    String urlERP = requestParam.getString("url_erp") + "erp-ws-main/ws/" + endpoint;
                    try {
                        resERP = DataSourceType.dataSourceERP(urlERP, G.erpAuth(), payloadERP);
                    } catch (UnsupportedEncodingException e2) {
                    }

                    if (!resERP.isEmpty() && resERP.has("STATUS")
                            && resERP.getString("STATUS").equalsIgnoreCase("Y")) {
                        return ResponseData.buildResponse(200, "Y", "SUKSES MENYIMPAN DATA");
                    }
                }
            }
        } catch (Exception e) {
            Logger.getLogger(KonsolidasiServices.class.getName()).log(Level.SEVERE, e.getMessage(), e);
        }

        return ResponseData.buildResponse(400, "N", "GAGAL MENGUBAH DATA");
    }

    public Object uptDataLanggananERPMatch(JSONObject requestParam, JSONObject authParam) {
        String statusResult,
                idLangganan = requestParam.has("id_langganan") ? requestParam.getString("id_langganan") : "",
                funcName = "sch_konsol.match_data_langganan";

        String resCodeERP = "SET-MS-LANGGANAN-RELASI";
        JSONObject reqParamERP = new JSONObject();
        JSONObject payloadERP = new JSONObject();
        JSONObject resERP = new JSONObject();

        try {
            String resDataKonsolidasi = NativeQuery.queryFunctionDBString(funcName, requestParam);
            JSONObject initData = new JSONObject(resDataKonsolidasi);
            statusResult = initData.get("STATUS").toString();

            if (statusResult.equalsIgnoreCase("Y") && !idLangganan.equalsIgnoreCase("")) {
                if (requestParam.has("kode_langganan")
                        && !requestParam.getString("kode_langganan").equalsIgnoreCase("")) {
                    reqParamERP.put("KODE_RELASI", idLangganan);
                    reqParamERP.put("PROSES_KONSOL", "N");
                    reqParamERP.put("KODE_LANGGANAN", requestParam.getString("kode_langganan"));

                    payloadERP.put("responseCode", resCodeERP);
                    payloadERP.put("requestParam", reqParamERP);
                    payloadERP.put("authParam", authParam);

                    String endpoint = requestParam.getString("api_method").equalsIgnoreCase("standart-api")
                            ? "postdata"
                            : requestParam.getString("api_method").equalsIgnoreCase("custom-api") ? "postquery" : "";
                    String urlERP = requestParam.getString("url_erp") + "erp-ws-main/ws/" + endpoint;
                    try {
                        resERP = DataSourceType.dataSourceERP(urlERP, G.erpAuth(), payloadERP);
                    } catch (UnsupportedEncodingException e2) {
                    }
                }

                if (!resERP.isEmpty() && resERP.has("STATUS")
                        && resERP.getString("STATUS").equalsIgnoreCase("Y")) {
                    return ResponseData.buildResponse(200, "Y", "SUKSES MENGUBAH DATA");
                }
            }
        } catch (Exception e) {
            Logger.getLogger(KonsolidasiServices.class.getName()).log(Level.SEVERE, e.getMessage(), e);
        }

        return ResponseData.buildResponse(400, "N", "GAGAL MENGUBAH DATA");
    }

    public Object uptDataLanggananERPKoreksi(JSONObject requestParam, JSONObject authParam) {
        String statusResult, funcName = "sch_konsol.update_data_langganan";

        try {
            String resDataKonsolidasi = NativeQuery.queryFunctionDBString(funcName, requestParam);
            JSONObject initData = new JSONObject(resDataKonsolidasi);

            statusResult = initData.get("STATUS").toString();

            if (statusResult.equalsIgnoreCase("Y")) {
                return ResponseData.buildResponse(200, "Y", "SUKSES MENGUBAH DATA DATA");
            }
        } catch (Exception e) {
            Logger.getLogger(KonsolidasiServices.class.getName()).log(Level.SEVERE, e.getMessage(), e);
        }

        return ResponseData.buildResponse(400, "N", "GAGAL MENGUBAH DATA");
    }
}
