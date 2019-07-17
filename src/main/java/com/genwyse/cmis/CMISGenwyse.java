package com.genwyse.cmis;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.chemistry.opencmis.client.api.CmisObject;
import org.apache.chemistry.opencmis.client.api.Document;
import org.apache.chemistry.opencmis.client.api.FileableCmisObject;
import org.apache.chemistry.opencmis.client.api.Folder;
import org.apache.chemistry.opencmis.client.api.ItemIterable;
import org.apache.chemistry.opencmis.client.api.ObjectId;
import org.apache.chemistry.opencmis.client.api.ObjectType;
import org.apache.chemistry.opencmis.client.api.OperationContext;
import org.apache.chemistry.opencmis.client.api.Policy;
import org.apache.chemistry.opencmis.client.api.Property;
import org.apache.chemistry.opencmis.client.api.QueryResult;
import org.apache.chemistry.opencmis.client.api.QueryStatement;
import org.apache.chemistry.opencmis.client.api.Session;
import org.apache.chemistry.opencmis.client.util.ContentStreamUtils;
import org.apache.chemistry.opencmis.commons.PropertyIds;
import org.apache.chemistry.opencmis.commons.data.Ace;
import org.apache.chemistry.opencmis.commons.data.ContentStream;
import org.apache.chemistry.opencmis.commons.definitions.PropertyDefinition;
import org.apache.chemistry.opencmis.commons.enums.Updatability;
import org.apache.chemistry.opencmis.commons.enums.VersioningState;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConnectionException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisConstraintException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisContentAlreadyExistsException;
import org.apache.chemistry.opencmis.commons.exceptions.CmisVersioningException;
import org.apache.chemistry.opencmis.commons.impl.MimeTypes;
import org.apache.chemistry.opencmis.commons.impl.dataobjects.ContentStreamImpl;
import org.apache.log4j.Logger;

public class CMISGenwyse {
  private static final Logger logger = Logger.getLogger(CMISGenwyse.class);
  
  public static final String classProp = "class";
  
  /*
   * Comportement si le document existe déjà
   */
  public enum Versionning {
    None,   // Génère une erreur
    Minor,  // Import d'une version mineure
    Major,  // Import d'une version majeure
  }

  private Session gedSession = null;
  private boolean correctNames = true;

  public CMISGenwyse(Session gedSession) {
    this(gedSession, true);
  }
  
  public CMISGenwyse(Session gedSession, boolean correctNames) {
    this.gedSession = gedSession;
    this.correctNames = correctNames;
  }
  
  public static void moveObject (FileableCmisObject item, Folder fromFolder, Folder toFolder) 
  {
    if (logger.isDebugEnabled()) logger.debug("Move "+item.getName()+" from "+fromFolder.getName()+" to "+toFolder.getName());
    item.move(fromFolder, toFolder);
  }

  public FileableCmisObject lookupChildByProps ( Folder parent, Map<String,Object> props) {
    return lookupChildByProps (parent, props, false);
  }
  
  public FileableCmisObject lookupChildByProps (Folder parent, Map<String,Object> props, boolean use_regex) {
    FileableCmisObject child = null;
    String [] prop_names = props.keySet().toArray(new String[0]);
    for (CmisObject obj : parent.getChildren()) {
      boolean good_obj = true;
      for (String prop: prop_names) {
        Object wanted_prop = props.get(prop);
        // Cas particulier: classe
        if (classProp.equals(prop)) {
          if (!obj.getType().getId().equals((String) wanted_prop)) {
            good_obj = false;
            break;
          }
        }
        else {
          if (use_regex && wanted_prop instanceof String) {
            // Vérification avec regexp
            Property<?> objProp = obj.getProperty(prop);
            if (objProp==null) {
              // L'objet n'a pas la propriété
              good_obj = false;
              break;
            }
            String objPropValue = objProp.getValueAsString();
            String wanted_string = (String) wanted_prop;
            if (!objPropValue.matches(wanted_string)) {
              good_obj = false;
              break;
            }
          }
          else {
            Property<?> objProp = obj.getProperty(prop);
            if (objProp==null) {
              // L'objet n'a pas la propriété
              good_obj = false;
              break;
            }
            Object obj_prop = objProp.getValue();
            if (!wanted_prop.equals(obj_prop)) {
              // Les valeurs d'objets sont différentes mais si la valeur attendue est une chaine
              // on va tenter de convertir en String pour les attributs d'un autre type
              if (wanted_prop instanceof String) {
                String str_obj_prop = obj.getProperty(prop).getValueAsString();
                if (!wanted_prop.equals(str_obj_prop)) {
                  good_obj = false;
                  break;
                }
              }
            }
          }
        }
      }
      if (good_obj && obj instanceof FileableCmisObject) {
        child = (FileableCmisObject) obj;
        break;
      }
    }
    return child;
  }
  
  /*
   * Recherche un objet (document ou dossier, selon le type indiqué) dans une arborescence par ses propriétés.
   * Le premier objet répondant aux critères est retourné.
   */
  public CmisObject lookupDescendantByProps ( Folder rootFolder, String itemTypeId, Map<String,Object> props) {
    List<CmisObject> objects = lookupDescendantsByProps(rootFolder, itemTypeId, props, 1);
    if (objects.size()>0) {
      return objects.get(0);
    }
    else {
      return null;
    }
  }
  
  /*
   * Recherche des objets (documents ou dossiers , selon le type indiqué) dans une arborescence par leurs propriétés.
   */
  public List<CmisObject> lookupDescendantsByProps (Folder rootFolder, String itemTypeId, Map<String,Object> props, int maxCount) {
    // Exemple de requête :
    //   SELECT cmis:objectId
    //   FROM <type>
    //   WHERE
    //     IN_TREE(...)
    //     AND <prop1> = ... AND <prop2> = ... 
    //   ORDER BY cmid:name
    // 
    List<CmisObject> objects = new LinkedList<CmisObject>();
    
    List<Object> queryParameters = new LinkedList<Object> ();
    StringBuffer itemQuery = new StringBuffer();
    itemQuery.append(
      "SELECT cmis:objectId FROM " + itemTypeId + " WHERE " +
      "IN_TREE(?) "
    );
    queryParameters.add(rootFolder.getId());
    
    for (String key: props.keySet()) {
      itemQuery.append("AND ");
      itemQuery.append(key);
      itemQuery.append(" = ? ");
      queryParameters.add(props.get(key));
    }
    itemQuery.append(" ORDER BY cmis:name ");
    
    // Crée la requête
    QueryStatement qs = gedSession.createQueryStatement(itemQuery.toString());
    int iQueryParameter = 1;
    for (Object queryParameter : queryParameters) {
      if (queryParameter instanceof String) {
        qs.setString(iQueryParameter++, (String) queryParameter);
      }
      else {
        logger.error("Cas de type de propriété non implémenté: "+queryParameter.getClass().getName());
        qs.setString(iQueryParameter++, queryParameter.toString());
      }
    }
    
    // La requête compilée
    String statement = qs.toQueryString();
    logger.debug("Recherche d'objet dans l'arborescence: "+statement);
    ItemIterable<QueryResult> results = gedSession.query(statement, false);
    int itemCount = 0;
    for (QueryResult result : results) {
      itemCount++;
      if (itemCount>maxCount) {
        break;
      }
      String itemId = (String) result.getProperties().get(0).getFirstValue();
      CmisObject cmisObject = gedSession.getObject(itemId);
      objects.add(cmisObject);
    }
    logger.debug("Nombre d'item trouvés: "+itemCount);

    return objects;
  }
  
  /**
   * Recherche un fils par son nom
   * 
   * @param gedSession
   * @param parent
   * @param childName
   * @return
   */
  public CmisObject lookupChildByName(Folder parent, String childName) {
    if (childName==null) return null;
    for (CmisObject child: parent.getChildren()) {
      if (childName.equals(child.getName())) {
        return child;
      }
    }
    return null;
  }
  
  //TODO: voir utilisation de la 1e version + pourqueoi différente ???
  public Folder createCollection (Folder parent, String collection_class, Map<String,Object> props) {
    if (collection_class==null||"".equals(collection_class)) {
      collection_class = "cmis:folder";
    }
    
    props.put(PropertyIds.OBJECT_TYPE_ID, collection_class);
    if (logger.isDebugEnabled()) logger.debug("createCollection "+props.get(PropertyIds.NAME)+" in "+parent.getName());
    Folder coll = parent.createFolder(props);
    return coll;
  }
  
  public Folder createCollection (Folder parent, String collectionType, String title, CMISObjectProperties props, Rights rights, String location) throws CMISGenwyseAlreadyExistException, CMISGenwyseException  {
    if (props==null) {
      props = new CMISObjectProperties(gedSession);
    }
    if (collectionType==null||"".equals(collectionType)) {
      collectionType = "cmis:folder";
    }
    CmisObject cmisObject = createObject(parent, collectionType, title, props, rights, null, null, null, location);
    return (Folder) cmisObject;
  }
  
  private static ContentStream createFileContentStream (File contentFile) throws FileNotFoundException {
    // Attention: Problème avec ContentStreamUtils.createFileContentStream
    // Les fichiers csv passent à 267 cars ils passent, à 268 cars il ne passent pas
    // et génèrent une exception bizarre de flux déjà fermé lors de l'envoi au serveur. 
    // Un fichier pdf plus gros passe sans problème.
    //
    // => pb avec ContentStreamUtils.createFileContentStream ???
    //ContentStream contentStream = ContentStreamUtils.createFileContentStream(contentFile);
    String fileName = contentFile.getName();
    String fileExt = "";
    int i = fileName.lastIndexOf('.');
    if (i>0) {
      fileExt = fileName.substring(i+1);
    }
    String mimeType = MimeTypes.getMIMEType(fileExt);  
    InputStream stream = new FileInputStream(contentFile);
    ContentStream contentStream = new ContentStreamImpl(fileName, BigInteger.valueOf(contentFile.length()), mimeType, stream);
    return contentStream;
  }
  
  public Document createDocument ( Folder parent, String document_class, Map<String,Object> props, File content_file, String gedFilename, Versionning versionning) throws Exception {
    if (document_class==null||"".equals(document_class)) {
      document_class = "cmis:document";
    }
    props.put(PropertyIds.OBJECT_TYPE_ID, document_class);
    props.put(PropertyIds.NAME, gedFilename);

    // Contenu
    ContentStream contentStream = createFileContentStream(content_file);
    
    // Crée le document
    try {
      if (logger.isTraceEnabled()) {
        logger.trace("Import du document "+gedFilename);
        ArrayList<String> propNames = new ArrayList<String>(props.keySet());
        Collections.sort(propNames);
        for (String propName : propNames) {
          logger.trace("  "+propName+": "+props.get(propName));
        }
      }
      Document newDoc = parent.createDocument(props, contentStream, VersioningState.MINOR);
      return newDoc;
    } catch (CmisContentAlreadyExistsException e) {
      // Le document existe
      boolean createMajor = false;
      
      switch (versionning) {
      case None:
        throw new CMISGenwyseAlreadyExistException("Le document existe déjà: "+gedFilename);
      case Minor:
        createMajor = false;
        break;
      case Major:
        createMajor = true;
        break;
      }
      // Le document existe => mise à jour du contenu avec historique
      Map<String,Object> propsToSearch = new HashMap<String,Object> ();
      propsToSearch.put(PropertyIds.NAME, gedFilename);
      FileableCmisObject item = lookupChildByProps(parent, propsToSearch);
      if (item instanceof Document) {
        Document oldDoc = (Document) item;
        logger.debug("Le document existe : mise à jour de: "+oldDoc.getId());
        Document pwc = null;
        try {
          ObjectId pwcId = oldDoc.checkOut();
          pwc = (Document) gedSession.getObject(pwcId);
          contentStream = ContentStreamUtils.createFileContentStream(content_file);
          pwc.checkIn(createMajor, props, contentStream, "");
          pwc = null;
          return oldDoc;
        } catch (CmisVersioningException e1) {
          logger.error("Erreur CMIS: " + e1.getMessage());
          throw e1;
        } catch (Exception e1) {
        	logger.error("Exception non prévue: "+e1.getMessage());
          throw e1;
        } finally {
        	if (pwc!=null) {
        		pwc.cancelCheckOut(); // Pour ne pas laisser de document verrouillé
        	}
        }
      } 
    } catch (CmisConnectionException e) {
      logger.error("Erreur à la création du document "+e.getMessage());
      throw e;
    } finally {
      // Ferme le flux
      try {
    	  InputStream s = contentStream.getStream();
    	  if (s!=null) {
    		  s.close();
    	  }
      } catch (IOException e) {
        logger.error(e.getMessage());
      }
    }
    return null;
  }
  
  public CmisObject createObject (Folder parent, String objectType, String title, Map<String,Object> properties, Rights rights, byte[] documentContent, String filename, String mimeType, String location) throws CMISGenwyseException, CMISGenwyseAlreadyExistException {

    String what = "objet";
    
    try {
      Map<String, Object> objectProperties = convertProperties(objectType, properties, location);
      if (title!=null) {
        objectProperties.put(PropertyIds.NAME, title);
      }
      objectProperties.put(PropertyIds.OBJECT_TYPE_ID, objectType);
      
      // Corrige éventuellement le nom d'objet
      if (correctNames) {
        objectProperties.put(PropertyIds.NAME, correctName((String)objectProperties.get(PropertyIds.NAME)));
      }
      
      // Nom de l'objet
      String objectName = (String)objectProperties.get(PropertyIds.NAME);
      
      List<Ace> addAces = new LinkedList<Ace>();
      List<Ace> removeAces = new LinkedList<Ace>();
      List<Policy> policies = new LinkedList<Policy>();
      
      if (rights!=null) {
        // Droits spécifiques à appliquer
        // Liste des ACE
        for (String principalName: rights.keySet()) {
          List<String> principalRights = rights.get(principalName);
          Ace principalAce = gedSession.getObjectFactory().createAce(principalName, principalRights);
          addAces.add(principalAce);
        }
      }
      OperationContext oc = gedSession.createOperationContext();
      
      // Dossier ou document ?
      CmisObject object = null;
      if (documentContent!=null) {
        what = "document";
        // Dans le cas d'un document on peut être amené à demander la création d'un document dont le nom
        // existe déjà. Ceci existe dans les vraies GED, mais pas dans Alfresco.
        // On va donc selon la configuration utiliser un suffixe dans le nom (à la façon de share) ou générer
        // une erreur.
        //TODO : prendre en compte la config pour la gestion du suffixe
        int numOrdre = 0;
        while (true) {
          String actualName = numOrdre < 1 ? objectName : objectName + "-" + numOrdre;
          objectProperties.put(PropertyIds.NAME, actualName);
          
          // Le contenu du document
          InputStream documentContentStream = new ByteArrayInputStream(documentContent);
          ContentStream cs = new ContentStreamImpl(filename, BigInteger.valueOf(documentContent.length), mimeType, documentContentStream);

          try {
            object = parent.createDocument(objectProperties, cs, VersioningState.MAJOR, policies, addAces, removeAces, oc);
            break; // C'est bon
          } catch (CmisContentAlreadyExistsException e) {
            numOrdre++; // On retente avec le suffixe suivant
          }
          
        }
      }
      else {
        what = "dossier";
        object = parent.createFolder(objectProperties, policies, addAces, removeAces, oc);
      }
      
      if (object!=null) {
        logger.debug("["+location+"] Création de : "+object.getName()+": OK");
        return object;
      } else {
        logger.debug("["+location+"] Création de : "+what+": Echec");
        return null;
      }
    } catch (CmisConstraintException e) {
      String mess = "Erreur lors de la création d'un " + what + ": "+e.getMessage();
      logger.debug("["+location+"] " + mess);
      throw new CMISGenwyseException(mess);
    } catch (CmisContentAlreadyExistsException e) {
      throw new CMISGenwyseAlreadyExistException("Création d'un "+what, e);
    } catch (Exception e) {
      String mess = "Exception lors de la création d'un " + what + ": "+e.getMessage();
      logger.debug("["+location+"] " + mess);
      throw new CMISGenwyseException(mess);
    }
  }

  public static String buildItemDetailsURL(String gedRoot, CmisObject item) {
    String itemPage = "";
    if (item instanceof Document) {
      itemPage = "document-details";
    }
    else if (item instanceof Folder) {
      itemPage = "folder-details";
    }
    else {
      return "";
    }
    String itemId = item.getId();
    
    // Apparemment l'item Id a à la fin un n° de version qui ne passe pas sur l'URL
    Pattern p = Pattern.compile("(.*);[0-9]+\\.[0-9]+$");
    Matcher m = p.matcher(itemId);
    if (m.matches()) {
      // L'id est de la forme ...;nn.mm , on enlève la fin
      itemId = m.group(1);
    }
    return gedRoot + "/page/" + itemPage + "?nodeRef=workspace://SpacesStore/" + itemId;
    
  }
  
  public void debugPbProps1 () {
    ObjectType objectType = gedSession.getTypeDefinition("cmis:folder");
    logger.error("========================= controle =========================");
    for (String propName : objectType.getPropertyDefinitions().keySet()) {
      logger.error(propName);
      if ("etu:source_ged".contentEquals(propName)) {
        logger.error("???????????????");
      }
    }
    logger.error("============================================================");
  }
  
  public boolean updateObject (CmisObject object, CMISObjectProperties objectProperties, String data_location)
  {
    // Récupérer les valeurs actuelles des propriétés présentes dans les infos fournies

    // Pour construire les noms des champs dans la requête, si l'on a des types secondaires (aspects),
    // Il faut faire une jointure, et dans ce cas 
    // il semble obligatoire d'utiliser un alias pour les noms de table (dans le cas contraire
    // la jointure est rejetée). On construit donc la requête avec un alias systématique pour la table
    // principale et pour les champs des aspects.
    
    // Tableau de correspondance table / alias (les alias seront de la forme Tn)
    // et table de correspondance propriété => alias
    Map<String,String> aliasByTable = new HashMap<String,String>();
    Map<String,String> aliasByProp = new HashMap<String,String>();
    Map<String,String> aspectByProp = new HashMap<String,String>();
    
    int currentTable = 1;
    ObjectType objectType = object.getType();
    String alias = "T"+currentTable++;
    String objectTypeName = objectType.getQueryName();
    aliasByTable.put(objectTypeName, alias);
    // Recherche des propriétés du type principal
    for (String propName : objectType.getPropertyDefinitions().keySet()) {
      // Pour chaque propriété de l'alias
      aliasByProp.put(propName, alias);
    }
    
    // A-t-on des types secondaires ?
    @SuppressWarnings("unchecked")
    List<String> secondaryTypes = (List<String>) objectProperties.get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
    if (secondaryTypes!=null) {
      for (String aspectName : secondaryTypes) {
        ObjectType aspect = gedSession.getTypeDefinition(aspectName);
        String queryName = aspect.getQueryName();
        if (!aliasByTable.containsKey(queryName)) { // Ne pas remettre le type principal
          alias = "T"+currentTable++;
          aliasByTable.put(queryName, alias);
          
          // Recherche des propriétés de l'aspect
          for (String propName : aspect.getPropertyDefinitions().keySet()) {
            // Pour chaque propriété de l'alias pas déjà présente
            if (!aliasByProp.containsKey(propName)) {
              aliasByProp.put(propName, alias);
              aspectByProp.put(propName, aspectName);
            }
          }
        }
      }
    }
    
    // Corrige éventuellement le nom d'objet
    if (correctNames) {
      objectProperties.put(PropertyIds.NAME, correctName((String)objectProperties.get(PropertyIds.NAME)));
    }
    
    // L'objet est-il un document (comportement différent dans ce cas)
    boolean isDocument = (object instanceof Document);
    
    StringBuffer queryBuffer = new StringBuffer();
    boolean first = true;
    queryBuffer.append("SELECT ");
    for (String propName: objectProperties.keySet()) {
      if (propName.contentEquals(PropertyIds.SECONDARY_OBJECT_TYPE_IDS)) {
        // On ne récupère pas cette propriété qui n'est pas un champ mais un type ou un aspect
        continue;
      }
      if (first) first = false;
      else queryBuffer.append(",");
      
      // A quel type appartient la propriété ?
      // NB: En cas de doute on prend le type principal
      queryBuffer.append(aliasByProp.get(propName));
      queryBuffer.append(".");
      queryBuffer.append(propName);
    }
    queryBuffer.append(" FROM ");
    queryBuffer.append(objectType.getQueryName());
    queryBuffer.append(" AS ");
    queryBuffer.append(aliasByTable.get(objectType.getQueryName()));
    
    // Les jointures avec les aspects
    if (aliasByTable.size()>1) {
      for (String tableName : aliasByTable.keySet()) {
        if (!tableName.equals(objectTypeName)) {
          queryBuffer.append(" LEFT JOIN ");
          queryBuffer.append(tableName);
          queryBuffer.append(" AS ");
          queryBuffer.append(aliasByTable.get(tableName));
          queryBuffer.append(" ON ");
          queryBuffer.append(aliasByTable.get(tableName));
          queryBuffer.append(".cmis:objectId = ");
          queryBuffer.append(aliasByTable.get(objectTypeName));
          queryBuffer.append(".cmis:objectId");
          queryBuffer.append(" ");
        }
      }
    }
    
    queryBuffer.append(" WHERE ");
    queryBuffer.append(aliasByTable.get(objectTypeName));
    queryBuffer.append(".cmis:objectId = ?");
    QueryStatement qs = gedSession.createQueryStatement(queryBuffer.toString());
    qs.setString(1, object.getId());
    ItemIterable<QueryResult> results = gedSession.query(qs.toQueryString(), false);
    if (results.iterator().hasNext()) {
      // Le document/dossier existe (ce devrait être le cas!)
      QueryResult result = results.iterator().next();
      
      // Prépare les valeurs à modifier 
      
      // On convertit d'abord les données fournies
      // NB: apparemment le type custom éventuel est dans les cmis:secondaryObjectTypeIds ?? 
      objectProperties = convertProperties(objectType.getBaseTypeId().value(), objectProperties, data_location);
      
      // Recherche des propriétés qui doivent être mises à jour
      Map<String,Object> propsToSet = new HashMap<String,Object>();
      for (String propName: objectProperties.keySet()) {
        if (PropertyIds.SECONDARY_OBJECT_TYPE_IDS.equals(propName)) {
          continue; // Les types secondaires ne sont pas une vraie propriété et on ne les change pas ici
        }
        Object oldPropValue = result.getPropertyValueById(propName);
        Object newPropValue = objectProperties.get(propName);
        Property<?> prop = object.getProperty(propName);
        boolean toSet = false;
        if (prop==null) {
          // L'objet n'a pas la propriété, il faut la créer (avec l'aspect)
          String aspectName = aspectByProp.get(propName);
          if (aspectName!=null) {
            // L'ajouter aux types secondaires
            @SuppressWarnings("unchecked")
            List<String> addedSecondaryTypes = (List<String>) propsToSet.get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
            if (addedSecondaryTypes==null) {
              addedSecondaryTypes = new LinkedList<String>();
            }
            if (!addedSecondaryTypes.contains(aspectName)) {
              addedSecondaryTypes.add(aspectName);
              propsToSet.put(PropertyIds.SECONDARY_OBJECT_TYPE_IDS, addedSecondaryTypes);
            }
          }
          toSet = true;
        }
        else {
          PropertyDefinition<?> propDef = prop.getDefinition();
          
          // Si la propriété n'est pas modifiable on laisse tomber
          if (propDef.getUpdatability()!=Updatability.READWRITE) {
            continue;
          }
          
          if (oldPropValue==null && newPropValue!=null) {
            toSet = true;
          }
          else if (oldPropValue==null && newPropValue==null) {
          }
          else if (newPropValue==null && oldPropValue.equals("")) {
            // On a une chaine vide, et la nouvelle valeur est null, on n'y touche pas
          } 
          else if (oldPropValue!=null && newPropValue==null) {
            // On vide une propriété
            toSet = true;
          }
          else {
            // Pour les documents, la propriété NAME n'est pas significative car elle peut 
            // avoir été suffixée. 
            if (isDocument) {
              if (propName.equals(PropertyIds.NAME)) {
                if (((String)oldPropValue).startsWith((String)newPropValue)) {
                  // Le nom enregistré est dérivé du nom à avoir => OK
                  continue;
                }
              }
            }
            
            // Compare les valeurs
            // Selon le type de donnée la comparaison diffère
            switch (propDef.getPropertyType()) {
            case STRING:
            case BOOLEAN:
              toSet = !oldPropValue.equals(newPropValue);
              break;
            case INTEGER: {
                Integer newInteger = (Integer) newPropValue;
                BigInteger oldBigInteger = (BigInteger) oldPropValue;
                toSet = newInteger.intValue() != oldBigInteger.intValue();
    
              }
              break;  
            case DATETIME:
              {
                Date newDate = (Date) newPropValue;
                GregorianCalendar oldCal = (GregorianCalendar) oldPropValue;
                toSet = newDate.getTime() != oldCal.getTimeInMillis();
              }
              break;
            default:
              toSet = !oldPropValue.equals(newPropValue);
            }
          }
        }
        if (toSet) {
          // Valeurs différentes
          propsToSet.put(propName, newPropValue);
        }
      }
      
      if (propsToSet.size()>0) {
        // Modification du dossier
        object.updateProperties(propsToSet);
        logger.debug("["+data_location+"] doUpdate(): student "+object.getName()+" "+object.getId()+" was updated.");
        return true;
      }
      else {
        return false; // Pas de mise à jour
      }
    }
    return false; // NB: on ne devrait pas passer ici
  }
  
  // TODO: voir pourquoi ceci n'est pas dans CMISObjectProperties
  public CMISObjectProperties convertProperties(String objectTypeName, Map<String,Object> propertyValues, String location) {
    // Le type de certaines propriétés peut différer dans la base CMIS de ce qui est fourni
    // dans les propriétés d'indexation, par exemple un entier peut être fourni alors 
    // que la GED attend une String (cas du code étudiant, Integer dans DocuShare et String
    // dans Alfresco).
    CMISObjectProperties objectProperties = new CMISObjectProperties(gedSession);
   
    // Les définitions des propriétés de l'objet
    ObjectType objectType = gedSession.getTypeDefinition(objectTypeName);
    Map<String, PropertyDefinition<?>> propDefs = new HashMap<String, PropertyDefinition<?>>();
    propDefs.putAll(objectType.getPropertyDefinitions());
    
    // Ajout des définitions des propriétés des types secondaires
    @SuppressWarnings("unchecked")
    List<String> secondaryTypes = (List<String>) propertyValues.get(PropertyIds.SECONDARY_OBJECT_TYPE_IDS);
    if (secondaryTypes!=null) {
      for (String secondaryTypeName: secondaryTypes) {
        ObjectType secondaryType = gedSession.getTypeDefinition(secondaryTypeName);
        Map<String, PropertyDefinition<?>> secondaryPropDefs = secondaryType.getPropertyDefinitions();
        propDefs.putAll(secondaryPropDefs);
      }
    }   
    if (propertyValues != null) {
      for (String propName : propertyValues.keySet()) {
        Object propValue = propertyValues.get(propName);
        propValue = objectProperties.convertPropValue(propDefs, propName, propValue, location);
        objectProperties.put(propName, propValue);
      }
    }
    return objectProperties;
  }
  
  public String correctName(String objectName) {
    // Documentation Alfresco https://docs.alfresco.com/6.1/tasks/library-create-folder.html
    // The folder name does not support the following special characters: * " < > \ / . ? : and |
    // ! The folder name can include a period as long as it is not the last character.
    // NB: En réalité c'est la même chose pour le blanc mais ils n'y ont pas pensé...
    if (objectName==null) {
      return null;
    }
    
    // Suppression des caractères interdits partout :
    String correctedName = objectName.replaceAll("[*\"<>\\\\/?|]", "");
    
    // Suppression des caractères interdits à la fin (normalement, le point et l'espace)
    correctedName = correctedName.replaceAll("[ .]+$","");
    if (!objectName.equals(correctedName)) {
      logger.warn("Nom corrigé: "+objectName+" => "+correctedName);
    }
    return correctedName;
  }
  
  public Document createDocument (Folder parent, String objectType, String title, CMISObjectProperties props, Rights rights, byte[] documentContent, String filename, String mimeType, String location) throws CMISGenwyseAlreadyExistException, CMISGenwyseException {
    if (objectType==null||"".equals(objectType)) {
      objectType = "cmis:document";
    }
    if (props==null) {
      props = new CMISObjectProperties(gedSession);
    }
    CmisObject cmisObject = createObject(parent, objectType, title, props, rights, documentContent, filename, mimeType, location);
    return (Document) cmisObject;
  }
  public boolean updateFolder (Folder folder, CMISObjectProperties folderProperties, String dataLocation) {
    return updateObject (folder, folderProperties, dataLocation);
  }
  
  public boolean updateDocument (Document document, CMISObjectProperties documentProperties, String dataLocation) {
    return updateObject (document, documentProperties, dataLocation);
  }
  
  
  
}
