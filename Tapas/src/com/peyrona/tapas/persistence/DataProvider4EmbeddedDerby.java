/*
 * Copyright (C) 2010 Francisco José Morero Peyrona. All Rights Reserved.
 *
 * This file is part of Tapas project: http://code.google.com/p/tapas-tpv/
 *
 * GNU Classpath is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the free
 * Software Foundation; either version 3, or (at your option) any later version.
 *
 * Tapas is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE.  See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * Tapas; see the file COPYING.  If not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package com.peyrona.tapas.persistence;

import java.awt.Image;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.swing.ImageIcon;

/**
 * Esta clase proporciona almacenamiento de datos utilizando Derby DB embebido
 * como repositorio.
 * <p>
 * Al ser "package" el alcance (scope) de esta clase, sólo DataProvider puede
 * instanciarla.
 * 
 * @author Francisco Morero Peyrona
 */

// Sobre "embedded Derby":
// http://java.sun.com/developer/technicalArticles/J2SE/Desktop/javadb/

// Nota1: Las buenas prácticas dicen que se usen siempre PreparedStatements, pero
//        en una app como esta no hay merma apreciable de rendimiento por usar
//        concatenación de cadenas y en algunos casos se lee más claro. Así que
//        he decidido utilizar unas u otras según comodidad y legibilidad.

// Nota2: Sobre el manejo de excepciones en Java se ha discutido hasta la saciedad,
//        y no hay manera de poner de acuerdo a todo el mundo.
//        En esta ocasión yo he optado por no propagar las excepciones que 
//        normalmente denotan un problema grave y sobre las que el usuario no
//        puede hacer nada. En lugar de marear al usuario con información que no
//        entiende, prefiero guardar la exception en un log y abortar la aplicación.
//        Se podrían lanzar las Exceptions en lugar de manejarlas dentro de
//        de esta clase, pero como digo, la aplicación no puede hacer
//        nada con ellas y son sintomáticas de que algo grave está pasando, por
//        ello prefiero cortar por lo sano avisando y abortando la aplicación.

final class DataProvider4EmbeddedDerby implements DataProviderable
{
    private Connection        dbConn            = null;
    private PreparedStatement psInsertBillHead  = null;
    private PreparedStatement psInsertBillLines = null;

    //------------------------------------------------------------------------//

    DataProvider4EmbeddedDerby()
    {
    }

    @Override
    public void connect() throws IOException, ClassNotFoundException, SQLException
    {
        String   sDbPath       = getDbPath();
        boolean  bCreateTables = isNeededToCreateTables( sDbPath +"/tapas" );

        System.setProperty( "derby.system.home", sDbPath );
        Class.forName( "org.apache.derby.jdbc.EmbeddedDriver" );
        dbConn = DriverManager.getConnection( "jdbc:derby:tapas;user=admin;password=admin;create=true" );

        if( bCreateTables )
        {
            try
            {
                File file = new File( getClass().getResource( "derby_RDM.sql" ).toURI() );
                SQLExecutor sqlexc = new SQLExecutor( dbConn, file );
                            sqlexc.extecute();
            }
            catch( Exception ex )
            {
                deleteDirectory( new File( sDbPath ) );
                throw new SQLException( "Error procesing SQL file to create RDM." );
            }
        }

        // Estas son las dos operaciones más utilizadas: las ponemos en PreparedStatement
        psInsertBillHead  = dbConn.prepareStatement( "INSERT INTO APP.ventas "+
                                                     " (cliente, modo_pago, cuando, total) VALUES(?,?,?,?)",
                                                     Statement.RETURN_GENERATED_KEYS );

        psInsertBillLines = dbConn.prepareStatement( "INSERT INTO APP.ventas_detalle "+
                                                     " (id_venta, cantidad, producto, precio) VALUES (?,?,?,?)" );
    }

    @Override
    public void disconnect() throws SQLException
    {
        try
        {
            dbConn.close();    // dbConn.close() cierra automáticamente todos los recursos asociados
        }
        catch( SQLException ex )
        {
            throw ex;
        }
        finally
        {
            try
            {   // Así es como se cierra límpiamente Derby
                DriverManager.getConnection( "jdbc:derby:;shutdown=true" );
            }
            catch( SQLException ex )
            {
                /* Nada que hacer: un shutdown limpio siempre lanza una SQLException XJ015, la cual puede ser ignorada. */
            }
        }
    }

    @Override
    public Configuration getConfiguration() throws SQLException, IOException
    {
        Configuration conf = new Configuration();
        Statement     stmt = null;
        ResultSet     rs   = null;

        try
        {
            stmt = dbConn.createStatement();
            rs   = stmt.executeQuery( "SELECT * FROM APP.configuracion WHERE id_configuracion = 1" );

            if( rs.next() )
            {                
                conf.setPassword( rs.getString( "contrasena" ) );
                conf.setEmail( rs.getString( "email" ) );
                conf.setFullScreenMode( rs.getInt( "full_screen" ) != 0 );
                conf.setAutoAlignMode( rs.getInt( "auto_alinear" ) != 0 );
                conf.setTicketFooter( rs.getString( "ticket_pie" ) );
                conf.setTicketHeader( rs.getString( "ticket_cabecera" ) );
                conf.setTicketHeaderImage( imageFromBlob( rs.getBlob( "ticket_imagen" ) ) );
            }
        }
        catch( SQLException exc )
        {
            throw exc;
        }
        finally
        {
            if( stmt != null )    // stmt.close() cierra automáticamente los rs asociados
                try{ stmt.close(); } catch( SQLException se ) { /* Nada que hacer */ }
        }

        return conf;
    }

    @Override
    public void setConfiguration( Configuration config ) throws SQLException
    {
        PreparedStatement psUpdate = dbConn.prepareStatement(
            "UPDATE APP.configuracion "+
            " SET contrasena = ?, email = ?, full_screen = ?, auto_alinear = ?,"+
            "     ticket_imagen = ?, ticket_cabecera = ?, ticket_pie = ?"+
            " WHERE id_configuracion = 1");

        Blob blobImage = imageToBlob( config.getTicketHeaderImage() );

        psUpdate.setString( 1, config.getPassword() );
        psUpdate.setString( 2, config.getEmail() );
        psUpdate.setInt(    3, (config.isFullScreenSelected() ? 1 : 0) );
        psUpdate.setInt(    4, (config.isAutoAlignSelected()  ? 1 : 0) );
        psUpdate.setBlob(   5, blobImage );
        psUpdate.setString( 6, config.getTicketHeader() );
        psUpdate.setString( 7, config.getTicketFooter() );
        psUpdate.executeUpdate();
        psUpdate.close();

        if( blobImage != null )
            blobImage.free();
    }

    @Override
    public List<Article> getCategoriesAndProducts() throws ClassNotFoundException, IOException, SQLException
    {
        List<Article> lstArticles = new ArrayList<Article>();
        String sQuery = "SELECT APP.categorias.nombre AS CatNombre, APP.categorias.icono AS CatIcono, APP.productos.*"+
                        "   FROM APP.categorias, APP.productos"+
                        "   WHERE APP.categorias.id_categoria = APP.productos.id_categoria"+
                        "   ORDER BY APP.categorias.id_categoria, APP.productos.nombre";
        Statement stmt = null;
        ResultSet rs   = null;

        try
        {
            stmt = dbConn.createStatement();
            rs   = stmt.executeQuery( sQuery );

            while( rs.next() )
            {
                Article category = new Article();
                        category.setCaption( rs.getString( "CatNombre" ) );

                // Buscamos si la categoría ya había sido añadida
                // (El método Article::equals() NO tiene en cuenta el icono)
                int nIndex = lstArticles.indexOf( category );

                // De no ser así, es una categoría nueva => añadir la inf que falta (el icono)
                if( nIndex == -1 )
                {
                    category.setIcon( iconFromBlob( rs.getBlob( "CatIcono" ) ) );
                    lstArticles.add( category );
                }
                
                // Además siempre hay un producto en la misma línea => actualizar los campos de producto
                Article product = new Article();
                        product.setCaption( rs.getString( "nombre" ) );
                        product.setDescription( rs.getString( "descripcion" ) );
                        product.setPrice( rs.getBigDecimal( "precio" ) );
                        product.setIcon( iconFromBlob( rs.getBlob( "icono" ) ) );
                lstArticles.get( lstArticles.size() - 1 ).addToSubMenu( product );
            }
        }
        catch( SQLException exc )
        {
            throw exc;
        }
        finally
        {
            if( stmt != null )    // stmt.close() cierra automáticamente los rs asociados
                try{ stmt.close(); } catch( SQLException se ) { /* Nothing to do */ }
        }

        return lstArticles;
    }

    @Override
    public void setCategoriesAndProducts( List<Article> articles ) throws IOException, SQLException
    {
        PreparedStatement psCategories = dbConn.prepareStatement(
                "INSERT INTO App.categorias (nombre, icono) VALUES (?, ?)",
                Statement.RETURN_GENERATED_KEYS );

        PreparedStatement psProducts = dbConn.prepareStatement(
                "INSERT INTO App.productos (id_categoria, nombre, descripcion, precio, icono) VALUES (?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS );

        // Al ser la relación con la tabla Productos ON DELETE CASCADE, al borrar
        // las categorías, se borran automáticamente los productos
        executeCommand( "DELETE FROM App.categorias" );

        for( Article category : articles )
        {
            Blob blobIcon = iconToBlob( category.getIcon() );

            psCategories.clearParameters();
            psCategories.setString( 1, category.getCaption() );
            psCategories.setBlob(   2, blobIcon );
            psCategories.executeUpdate();

            if( blobIcon != null )
                blobIcon.free();

            ResultSet rs = psCategories.getGeneratedKeys();

            if( rs.next() )    // Solo hay un record en este ResultSet
                category.setId( rs.getInt( 1 ) );

            rs.close();

            for( Article product : category.getSubMenu() )
            {
                blobIcon = iconToBlob( product.getIcon() );

                psProducts.clearParameters();
                psProducts.setInt(        1, category.getId() );
                psProducts.setString(     2, product.getCaption() );
                psProducts.setString(     3, product.getDescription() );
                psProducts.setBigDecimal( 4, product.getPrice() );
                psProducts.setBlob(       5, blobIcon );
                psProducts.executeUpdate();

                if( blobIcon != null )
                    blobIcon.free();
            }
        }

        psProducts.close();
        psCategories.close();
    }

    @Override
    public Bill insertBill( Bill bill ) throws SQLException
    {
        // Insertamos la cabecera de la venta (ticket)
        psInsertBillHead.clearParameters();
        psInsertBillHead.setString(     1, bill.getCustomer() );
        psInsertBillHead.setInt(        2, bill.getPayModeAsInt() );
        psInsertBillHead.setTimestamp(  3, new Timestamp( System.currentTimeMillis() ) );
        psInsertBillHead.setBigDecimal( 4, bill.getTotal() );
        psInsertBillHead.executeUpdate();

        ResultSet rs = psInsertBillHead.getGeneratedKeys();

        if( rs.next() )    // Solo hay un record en este ResultSet
            bill.setId( rs.getInt( 1 ) );

        rs.close();

        // Insertamos las líneas de detalle de la venta (ticket)
        for( BillLine line : bill.getLines() )
        {
            psInsertBillLines.clearParameters();
            psInsertBillLines.setInt(        1, bill.getId() );
            psInsertBillLines.setInt(        2, line.getQuantity() );
            psInsertBillLines.setString(     3, line.getItem() );
            psInsertBillLines.setBigDecimal( 4, line.getPrice() );
            psInsertBillLines.executeUpdate();
        }

        return bill;
    }

    @Override
    // Esta operación es tan poco frecuente que no merece la pena hacer un UPDATE
    public Bill updateBill( Bill bill ) throws SQLException
    {
        deleteBill( bill );
        return insertBill( bill );
    }

    @Override
    public void deleteBill( Bill bill ) throws SQLException
    {
        int nId = bill.getId();
        // La relación es ON CASCADE DELETE => los registros asociados en la
        // tabla ventas_detalle se borran automáticamente.
        executeCommand( "DELETE FROM APP.ventas WHERE ventas.id = " + nId );
    }


    @Override
    public List<Bill> findBills( Date dFrom, Date dTo, Bill.Payment[] payments, boolean bDelete ) throws SQLException
    {
        StringBuilder sbCondition = new StringBuilder( 512 );

        if( dFrom != null )
        {
            sbCondition.append( "APP.ventas.cuando >= " )
                       .append( new java.sql.Date( dFrom.getTime() ) );
        }

        if( dTo != null )
        {
            if( sbCondition.length() > 0 )
                sbCondition.append(  " AND " );

            sbCondition.append( "APP.ventas.cuando <= " )
                       .append( new java.sql.Date( dTo.getTime() ) );
        }

        if( payments != null && payments.length > 0 )
        {
            if( sbCondition.length() > 0 )
                sbCondition.append(  " AND " );

            sbCondition.append( "APP.ventas.modo_pago IN (" );

            for( Bill.Payment p : payments )
                sbCondition.append( p ).append( ',' );

            sbCondition.deleteCharAt( sbCondition.length() - 1 );   // Quitamos el último ','
            sbCondition.append( ")" );
        }

        return resultSetToBillsList( sbCondition.toString(), bDelete );
    }

    @Override
    public List<Bill> findBillsByCustomer( String sCustomerPattern ) throws SQLException
    {
        return resultSetToBillsList( "APP.ventas.cliente LIKE %"+ sCustomerPattern +"%", false );
    }

    //------------------------------------------------------------------------//
    // PRIVATE SCOPE

    /**
     * Establece el path para la DB: <appdir>/tapas/ y crea la carpeta si fuese necesario.
     */
    private String getDbPath() throws IOException
    {
        String  sAppDir  = System.getProperty( "user.dir", "." );
        String  sDbDir   = sAppDir + "/db";
        File    fDbDir   = new File( sDbDir );
        boolean bSuccess = true;

        if( ! fDbDir.exists() )
            bSuccess = fDbDir.mkdirs();

        bSuccess = bSuccess && fDbDir.canRead() && fDbDir.canWrite();

        if( ! bSuccess )
            throw new IOException( "Can't create db directory in ["+ sDbDir +"]" );

        return sDbDir;
    }

    /**
     * Borra el dir de la DB y todos sus sub-dirs.
     */
    private boolean deleteDirectory( File path )
    {
        if( path.exists() )
        {
            File[] files = path.listFiles();

            for( int i = 0; i < files.length; i++ )
            {
                if( files[i].isDirectory() )
                    deleteDirectory( files[i] );
                else
                    files[i].delete();
            }
        }

        return (path.delete());
    }

    private boolean isNeededToCreateTables( String sDbLocation )
    {
        File fDbDir = new File( sDbLocation );

        return ! fDbDir.exists();
    }

    private void executeCommand( String sCmd ) throws SQLException
    {
        Statement stmt = null;

        try
        {
            stmt = dbConn.createStatement();
            stmt.execute( sCmd );
        }
        catch( SQLException se )
        {
            throw se;
        }
        finally
        {
            if( stmt != null )
                try{ stmt.close(); } catch( SQLException se ) { /* Nothing to do */ }
        }
    }

    private Blob imageToBlob( Image image ) throws SQLException
    {
        if( image == null )
            return iconToBlob( null );

        return iconToBlob( new ImageIcon( image) );
    }

    private Blob iconToBlob( ImageIcon icon ) throws SQLException
    {
        Blob blob = null;

        if( icon != null )
        {
            blob = dbConn.createBlob();

            try
            {
                ObjectOutputStream oos;
                                   oos = new ObjectOutputStream( blob.setBinaryStream( 1 ) );
                                   oos.writeObject( icon );
                                   oos.close();
            }
            catch( Exception exc )
            {
                if( exc instanceof SQLException )
                    throw (SQLException) exc;
                else
                    throw new SQLException( exc.getMessage() );
            }
        }

        return blob;
    }

    private Image imageFromBlob( Blob blob ) throws SQLException
    {
        ImageIcon icon = iconFromBlob( blob );

        return (icon == null ? null : icon.getImage());
    }

    private ImageIcon iconFromBlob( Blob blob ) throws SQLException
    {
        ImageIcon icon = null;

        if( blob != null )
        {
            if( blob.length() > 0 )
            {
                try
                {
                    ObjectInputStream ois = new ObjectInputStream( blob.getBinaryStream() );

                    icon = (ImageIcon) ois.readObject();
                    ois.close();
                }
                catch( Exception exc )
                {
                    if( exc instanceof SQLException )
                        throw (SQLException) exc;
                    else
                        throw new SQLException( exc.getMessage() );
                }
            }

            blob.free();
        }

        return icon;
    }
// TODO: probar este método
    private List<Bill> resultSetToBillsList( String sCondition, boolean bDelete ) throws SQLException
    {
        ArrayList<Bill> bills = new ArrayList<Bill>();

        String    sQuery  = "SELECT * FROM APP.VENTAS, APP.VENTAS_DETALLE"+
                            "   WHERE (APP.VENTAS.ID_VENTA = APP.VENTAS_DETALLE.ID_VENTA)"+
                            "     AND ("+ sCondition + ")";
        Statement stmt    = dbConn.createStatement();
        ResultSet rs      = stmt.executeQuery( sQuery + sCondition );
        Bill      bill    = null;
        int       nBillId = -1;

        while( rs.next() )
        {
            if( nBillId != rs.getInt( "ID_VENTA" ) )    // Nuevo Bill
            {
                nBillId = rs.getInt( "ID_VENTA" );

                bill = new Bill();
                bill.setId( nBillId );
                bill.setCustomer( rs.getString( "CLIENTE" ) );
                bill.setPayModeAsInt( rs.getInt( "MODO_PAGO" ) );
                bill.setWhen( rs.getLong( "CUANDO" ) );
                bills.add( bill );
            }

            BillLine billLine = new BillLine( rs.getInt( "CANTIDAD" ),
                                              rs.getString( "PRODUCTO" ), 
                                              rs.getBigDecimal( "PRECIO" ) );
            bill.addLine( billLine );
        }

        stmt.close();    // stmt.close() cierra automáticamente los rs asociados

        if( bDelete )
        {   // Al borrar VENTAS, VENTAS_DETALLE se borra automáticamente (ON DELETE CASCADE)
            Statement stmtDel = dbConn.createStatement();
                      stmtDel.executeUpdate( "DELETE FROM APP.VENTAS WHERE "+ sCondition );
                      stmtDel.close();
        }

        return bills;
    }
}