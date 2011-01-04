/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package facturatron.facturacion;


import facturatron.Dominio.Configuracion;
import facturatron.MVC.JDBCDAOSupport;
import facturatron.Dominio.Factura;
import facturatron.Dominio.Persona;
import facturatron.Dominio.Renglon;
import facturatron.MVC.DAO;
import facturatron.Principal.VisorPdf;
import facturatron.cliente.ClienteDao;
import facturatron.config.ConfiguracionDao;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.sql.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import mx.bigdata.sat.cfd.schema.Comprobante.Conceptos;
import mx.bigdata.sat.cfd.schema.Comprobante.Conceptos.Concepto;
import mx.bigdata.sat.cfd.schema.Comprobante.Impuestos;
import mx.bigdata.sat.cfd.schema.Comprobante.Impuestos.Traslados;
import mx.bigdata.sat.cfd.schema.Comprobante.Impuestos.Traslados.Traslado;
import mx.bigdata.sat.cfd.schema.ObjectFactory;
import phesus.facturatron.lib.CFDFactory;
import phesus.facturatron.lib.entities.CFDv2Tron;
import phesus.facturatron.lib.entities.ComprobanteTron;


/**
 *
 * @author saul
 */
public class FacturaDao extends Factura implements DAO<Integer,Factura>{

    private Configuracion config;

    /** Carga el archivo de configuración y lo almacena en memoria.
     * Importante: Sólo carga el archivo de configuración una vez por instancia, las demás
     * las toma del atributo config (caché)
     * @return
     */
    Configuracion getConfig() {
        if(config == null) { config = (new ConfiguracionDao()).load(); }
        return config;
    }

     private CFDv2Tron sellar() throws URISyntaxException, Exception {
        ComprobanteTron comp = toComprobanteTron();

        Configuracion config = getConfig();
        comp.setPassKey(config.getpassCer());
        comp.setURIKey(new URI("file:///"+config.getpathKey().replace("\\", "/")));
        comp.setURICer(new URI("file:///"+config.getpathCer().replace("\\", "/")));

        CFDFactory cfdf = new CFDFactory();
        CFDv2Tron cfd = cfdf.toCFD(comp);

        setSello(comp.getSello());
        setXml(cfd.getXML());

        return cfd;
     }
     private void distribuir(CFDv2Tron cfd) throws Exception {

        Configuracion cfg = getConfig();
        String serie = cfd.getComprobante().getSerie();
        String folio = cfd.getComprobante().getFolio();
        cfd.toPDFFile(cfg.getPathPlantilla(), getPdfPath(serie, folio));
        cfd.toXMLFILE(                        getXmlPath(serie, folio));
        //cfd.showPreview(cfg.getPathPlantilla());
        VisorPdf.abrir(getPdfPath(serie, folio));
     }

     public ComprobanteTron toComprobanteTron() {

        MathContext mc = MathContext.DECIMAL32;
        ComprobanteTron comp = new ComprobanteTron();
        comp.setVersion(getVersion());
        comp.setFecha(new java.util.Date());
        comp.setSerie(getSerie());
        comp.setFolio(String.valueOf(getFolio()));
        comp.setNoAprobacion(getNoAprobacion());
        comp.setAnoAprobacion(BigInteger.valueOf(getAnoAprobacion()));
        comp.setFormaDePago(getFormaDePago());
        comp.setSubTotal(new BigDecimal(getSubtotal()).round(mc));
        comp.setTotal(new BigDecimal(getTotal()).round(mc));
        comp.setDescuento(new BigDecimal(getDescuentoTasa0()).round(mc).add(new BigDecimal(getDescuentoTasa16()).round(mc)));
        comp.setTipoDeComprobante(getTipoDeComprobante());
        Persona emSucursal = getEmisorSucursal();
        if(emSucursal.getEstado().isEmpty()) { emSucursal = null; }
        comp.setEmisor(getEmisor().toEmisor(emSucursal));
        comp.setReceptor(getReceptor().toReceptor());
        comp.setConceptos(getConceptos());
        comp.setImpuestos(getImpuestos());
         
        return comp;
     }

     public Conceptos getConceptos() {
        Conceptos cps = (new ObjectFactory()).createComprobanteConceptos();
        List<Concepto> list = cps.getConcepto();
        for (Renglon renglon : getRenglones()) {
            if(renglon.getImporte() == 0d || renglon.getImporte() == null) { continue; }
            list.add(renglon.toConcepto());
        }
        return cps;
     }

     public Impuestos getImpuestos() {
        MathContext mc = MathContext.DECIMAL32;
        ObjectFactory of = new ObjectFactory();
        Impuestos imps = of.createComprobanteImpuestos();
        Traslados trs = of.createComprobanteImpuestosTraslados();
        List<Traslado> list = trs.getTraslado();

        Traslado t1 = of.createComprobanteImpuestosTrasladosTraslado();
        t1.setImporte(new BigDecimal(getIvaTrasladado()).round(mc));
        t1.setImpuesto("IVA");
        t1.setTasa(new BigDecimal("16.00"));
        list.add(t1);
        imps.setTraslados(trs);
        imps.setTotalImpuestosTrasladados(new BigDecimal(getIvaTrasladado()).round(mc));
        return imps;
     }

     @Override
     public void setTotal(Double total) {
        super.setTotal(total);
        setChanged();
        notifyObservers();
     }

    @Override
     public void setDescuentoTasa0(Double descuento) {
         super.setDescuentoTasa0(descuento);
         setChanged();
         notifyObservers();
     }

    @Override
     public void setDescuentoTasa16(Double descuento) {
         super.setDescuentoTasa16(descuento);
         setChanged();
         notifyObservers();
     }

     @Override
     public void setReceptor(Persona receptor) {
        super.setReceptor(receptor);
        setChanged();
        notifyObservers();
     }

     public ArrayList<Factura> findAll(Date fechaInicial, Date fechaFinal){
         JDBCDAOSupport bd = getBD();
         try {

            bd.conectar();
            PreparedStatement ps = bd.getCon().prepareStatement("SELECT * FROM comprobante WHERE fecha >= ? and fecha <= ?");
            ps.setDate(1, fechaInicial);
            ps.setDate(2, fechaFinal);
            ResultSet rs = ps.executeQuery();
            ArrayList<Factura> ret = new ArrayList<Factura>();
            Factura bean;
            while (rs.next()) {
                
                bean = new Factura();
                bean.setId(rs.getInt("id"));
                bean.setVersion(rs.getString("version"));
                bean.setFecha(rs.getDate("fecha"));
                bean.setSerie(rs.getString("serie"));
                bean.setFolio(BigInteger.valueOf(rs.getLong("folio")));
                bean.setSello(rs.getString("sello"));
                bean.setNoCertificado(rs.getString("noCertificado"));
                bean.setNoAprobacion(BigInteger.valueOf(rs.getInt("noAprobacion")));
                bean.setAnoAprobacion(rs.getInt("anoAprobacion"));
                bean.setFormaDePago(rs.getString("formaDePago"));
                bean.setSubtotal(rs.getDouble("subtotal"));
                bean.setTotal(rs.getDouble("total"));
                bean.setDescuentoTasa0(rs.getDouble("descuentoTasa0"));
                bean.setDescuentoTasa16(rs.getDouble("descuentoTasa16"));
                bean.setTipoDeComprobante(rs.getString("tipoDeComprobante"));
                bean.setEmisor((new ClienteDao()).findBy(rs.getInt("idemisor")));
                bean.setReceptor((new ClienteDao()).findBy(rs.getInt("idReceptor")));
                bean.setIvaTrasladado(rs.getDouble("ivaTrasladado"));
                bean.setCertificado(rs.getString("certificado"));
                bean.setMotivoDescuento(rs.getString("motivoDescuento"));
                bean.setXml(rs.getString("xml"));
                ret.add(bean);

             }
             return ret;
        }catch(Exception ex){

            Logger.getLogger(FacturaDao.class.getName()).log(Level.SEVERE, null, ex); 
             
        } finally{  //si falla o no falla se tiene que desconectar
            bd.desconectar();
        }
        return null;
     }

    @Override
    public void persist() throws SQLException {
        JDBCDAOSupport bd = null;
        try {
            CFDv2Tron comprobanteSellado;
            bd = getBD();
            bd.conectar(true);
            bd.getCon().setAutoCommit(false);

            setFolio(SerieDao.nextId(bd.getCon()));

            comprobanteSellado = sellar();

            PreparedStatement ps = bd.getCon().prepareStatement("insert into comprobante " +
                    "(version,fecha,serie,folio,sello,noCertificado,noAprobacion,anoAprobacion," +
                    "formaDePago,subtotal,total,descuentoTasa0,descuentoTasa16,tipoDeComprobante,idEmisor, idReceptor," +
                    "ivaTrasladado,certificado,motivoDescuento,xml) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");

            ps.setString(1, getVersion());
            ps.setDate(2, new java.sql.Date(getFecha().getTime()));
            ps.setString(3, getSerie());
            ps.setLong(4, getFolio().longValue());
            ps.setString(5, getSello());
            ps.setString(6, getNoCertificado());
            ps.setInt(7, getNoAprobacion().intValue());
            ps.setInt(8, getAnoAprobacion().intValue());
            ps.setString(9, getFormaDePago());
            ps.setDouble(10, getSubtotal());
            ps.setDouble(11, getTotal());
            ps.setDouble(12, getDescuentoTasa0());
            ps.setDouble(13, getDescuentoTasa16());
            ps.setString(14, getTipoDeComprobante());
            ps.setInt(15, getEmisor().getId());
            ps.setInt(16, getReceptor().getId());
            ps.setDouble(17, getIvaTrasladado());
            ps.setString(18, getCertificado());
            ps.setString(19, getMotivoDescuento());
            ps.setString(20, getXml());

            ps.execute();

            ResultSet keys = ps.getGeneratedKeys();
            keys.next();
            int idfactura = keys.getInt(1);
            keys.close();

            for (Renglon renglon : getRenglones()) {  //
                if(renglon.getImporte()<=0) { continue; }
                ps = bd.getCon().prepareStatement("insert into concepto (idComprobante,unidad,noIdentificacion,importe," +
                        "cantidad,descripcion,valorunitario,tasa0) VALUES (?,?,?,?,?,?,?,?)");
                ps.setInt(1, idfactura);
                ps.setString(2, renglon.getUnidad());
                ps.setString(3, renglon.getNoIdentificacion());
                ps.setDouble(4, renglon.getImporte());
                ps.setDouble(5, renglon.getCantidad());
                ps.setString(6, renglon.getDescripcion());
                ps.setDouble(7, renglon.getValorUniario());
                ps.setInt(8, renglon.getTasa0()?1:0);
                ps.execute();
            }
            distribuir(comprobanteSellado);
            bd.getCon().commit();

        } catch (Exception ex) {
            bd.getCon().rollback();
            throw new SQLException(ex);
        } finally {
            bd.desconectar();
        }
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public Factura findBy(Integer id) {
     try {
            JDBCDAOSupport bd = getBD();
            FacturaDao dao = new FacturaDao();
            bd.conectar();
            ResultSet rs = bd.getStmt().executeQuery("select * from comprobante where id = " + id);
            rs.next();
            dao.setId(id);
            dao.setVersion(rs.getString("version"));
            dao.setFecha(rs.getDate("fecha"));
            dao.setSerie(rs.getString("serie"));
            dao.setFolio(BigInteger.valueOf(rs.getLong("folio")));
            dao.setSello(rs.getString("sello"));
            dao.setNoCertificado(rs.getString("noCertificado"));
            dao.setNoAprobacion(BigInteger.valueOf(rs.getInt("noAprobacion")));
            dao.setAnoAprobacion(rs.getInt("anoAprobacion"));
            dao.setFormaDePago(rs.getString("formaDePago"));
            dao.setSubtotal(rs.getDouble("subtotal"));
            dao.setTotal(rs.getDouble("total"));
            dao.setDescuentoTasa0(rs.getDouble("descuentoTasa0"));
            dao.setDescuentoTasa16(rs.getDouble("descuentoTasa16"));
            dao.setTipoDeComprobante(rs.getString("tipoDeComprobante"));
            dao.setEmisor((new ClienteDao()).findBy(rs.getInt("idemisor")));
            dao.setReceptor((new ClienteDao()).findBy(rs.getInt("idReceptor")));
            dao.setIvaTrasladado(rs.getDouble("ivaTrasladado"));
            dao.setCertificado(rs.getString("certificado"));
            dao.setMotivoDescuento(rs.getString("motivoDescuento"));
            dao.setXml(rs.getString("xml"));

            rs = bd.getStmt().executeQuery("select * from concepto where id = "+id);//
            ArrayList <Renglon> renglones = new ArrayList <Renglon>();
            Renglon rb = new Renglon();
            while(rs.next()){
                rb = new Renglon();
                rb.setId(rs.getInt("id"));
                rb.setUnidad(rs.getString("unidad"));
                rb.setNoIdentificacion(rs.getString("noIdentificacion"));
                rb.setImporte(rs.getDouble("importe"));
                rb.setCantidad(rs.getDouble("cantidad"));
                rb.setDescripcion(rs.getString("descripcion"));
                rb.setValorUniario(rs.getDouble("valorUnitario"));
                rb.setTasa0(rs.getInt("tasa0")==1);
                renglones.add(rb);
            }
            dao.setRenglones(renglones);
            bd.desconectar();
            rs.close();
            return dao;
        } catch (SQLException ex) {
            Logger.getLogger(FacturaDao.class.getName()).log(Level.SEVERE, null, ex);
        }
        return null;
    }

    @Override
    public JDBCDAOSupport getBD() {
       return new JDBCDAOSupport();
    }

    @Override
    public List<Factura> findAll() {
        throw new UnsupportedOperationException("Not supported. Instead use findAll(Date fechaInicial, Date fechaFinal)");
    }

    String getReciboName() {
        return getReciboName(getSerie(), getFolio().toString());
    }

    String getReciboName(String serie, String folio) {
        serie                = (serie!=null?serie:"");
        String nombreRecibo  = "Factura"+serie+folio;
        return nombreRecibo;
    }

    String getPdfPath() {
        return getConfig().getPathPdf()+getReciboName()+".pdf";
    }

    String getXmlPath() {
        return getConfig().getPathXml()+getReciboName()+".xml";
    }
    String getPdfPath(String s, String f) { return getConfig().getPathPdf()+getReciboName(s,f)+".pdf"; }
    String getXmlPath(String s, String f) { return getConfig().getPathXml()+getReciboName(s,f)+".xml"; }

}
