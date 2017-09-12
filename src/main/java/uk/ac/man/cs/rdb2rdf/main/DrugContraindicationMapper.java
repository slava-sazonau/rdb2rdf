package uk.ac.man.cs.rdb2rdf.main;

import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.reasoner.InferenceType;
import org.semanticweb.owlapi.reasoner.OWLReasoner;
import org.semarglproject.vocab.OWL;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.*;

import static uk.ac.man.cs.rdb2rdf.main.CSV2OWLConverter.*;
import static uk.ac.man.cs.rdb2rdf.main.CSVReader.processCell;


/**
 * Created by slava on 04/09/17.
 */
public class DrugContraindicationMapper {

    private static final String IRI_NAME = "http://owl.cs.manchester.ac.uk/contraindication";
    private static final String TOP_CONTRAINDICATION = "Contraindication";
    private static final String GIVEN_CONTRAINDICATION = "GivenContraindication";
    private static final String INFERRED_CONTRAINDICATION = "InferredContraindication";


    private OWLOntologyManager manager;
    private OWLOntology ontology;
    private OWLDataFactory factory;
    private OWLReasoner reasoner;

    private Map<String, OWLClass> icd9Map;

    private Map<String, Contraindication> drug2ContrMap;





    public DrugContraindicationMapper(File drug2cond, File cond2ICD, File drug2cat)
            throws OWLOntologyCreationException {
        // create an ontology
        IRI iri = IRI.create(IRI_NAME);
        manager = OWLManager.createOWLOntologyManager();
        ontology = manager.createOntology(iri);
        factory = manager.getOWLDataFactory();
        init(drug2cond, cond2ICD, drug2cat);
    }

    private void init(File drug2condFile, File cond2ICDFile, File drug2catFile) {
        // map conditions to ICD
        Map<String, Set<String>> cond2ICDMap = new HashMap<>();
        final List<String[]> cond2ICDRows = CSVReader.read(cond2ICDFile, true);
        for (String[] row : cond2ICDRows) {
            String condID = processCell(row[2]);
            Set<String> codes = cond2ICDMap.get(condID);
            if (codes == null) {
                codes = new HashSet<>();
                cond2ICDMap.put(condID, codes);
            }
            String code = processCell(row[0]);
            codes.add(code);
        }

        // map drugs to ICD codes
        List<String[]> drug2condRows = CSVReader.read(drug2condFile, true);
        drug2ContrMap = new HashMap<>();
        for (String[] row : drug2condRows) {
            Contraindication contr = new Contraindication();
            contr.drugCode = processCell(row[1]);
            contr.drug = processCell(row[2]);
            String conditionId = processCell(row[3]);
            if (cond2ICDMap.containsKey(conditionId)) {
                contr.conditions = cond2ICDMap.get(conditionId);
            } else {
                contr.conditions = new HashSet<>();
            }
            contr.severity = processCell(row[7]);
            drug2ContrMap.put(contr.drugCode, contr);
        }

        // map drugs to categories
        final List<String[]> drug2catRows = CSVReader.read(drug2catFile, true);
        for (String[] row : drug2catRows) {
            String drugCode = processCell(row[0]);
            Contraindication contr = drug2ContrMap.get(drugCode);
            contr.drugCategory = processCell(row[3]);
        }

    }


    private void addICD9Ontology(File file) throws OWLOntologyCreationException {
        Out.p("Loading ICD9 terminology");
        OWLOntology icd9Ontology = manager.loadOntologyFromOntologyDocument(file);
        // get class hierarchy
        Out.p("Adding class hierarchy");
        Set<OWLSubClassOfAxiom> classAxioms = new HashSet<>();
        for (OWLAxiom axiom : icd9Ontology.getAxioms()) {
            if (axiom instanceof OWLSubClassOfAxiom) {
                OWLSubClassOfAxiom clAxiom = (OWLSubClassOfAxiom) axiom;
                classAxioms.add(clAxiom);
            }
        }
        manager.addAxioms(ontology, classAxioms);
        Out.p("Class axioms are added: " + classAxioms.size());
        // adding annotations
        addAnnotations(icd9Ontology);
    }


    private void addAnnotations(OWLOntology icd9Ontology) {
        // get annotations
        Out.p("Adding annotations");
        Set<OWLClass> cls = icd9Ontology.getClassesInSignature();
        Set<OWLAnnotationAssertionAxiom> labelAnnots = new HashSet<>();
        for (OWLClass cl : cls) {
            Set<OWLAnnotationAssertionAxiom> annots = icd9Ontology.getAnnotationAssertionAxioms(cl.getIRI());
            for (OWLAnnotationAssertionAxiom annot : annots) {
                if (annot.getProperty().getIRI().toString().contains("prefLabel")) {
                    labelAnnots.add(annot);
                }
            }
        }
        Out.p("Annotations are added: " + labelAnnots.size());
        manager.addAxioms(ontology, labelAnnots);
        // create mapping
        icd9Map = new HashMap<>();
        Out.p("Creating ICD9 code-class mappings");
        for (OWLClass cl : cls) {
            String clCode = cl.getIRI().toString().replace("http://purl.bioontology.org/ontology/ICD9CM/", "");
            icd9Map.put(clCode, cl);
        }
    }


    private void computeClassHierarchy() throws Exception {
        // process the ontology by the reasoner
        Out.p("\nInitialising the reasoner");
        reasoner = ReasonerLoader.initReasoner(ontology);
        reasoner.precomputeInferences(InferenceType.CLASS_HIERARCHY);
    }


    private void createOntology(File ontFile) throws IOException, OWLOntologyStorageException {
        // populate the ontology
        // classes
        IRI topDrugClassIRI = IRI.create(IRI_NAME + IRI_DELIMITER + TOP_MEDICINE);
        OWLClass topDrugClass = factory.getOWLClass(topDrugClassIRI);
        IRI topContrClassIRI = IRI.create(IRI_NAME + IRI_DELIMITER + TOP_CONTRAINDICATION);
        OWLClass topContrClass = factory.getOWLClass(topContrClassIRI);
        IRI givenContrClassIRI = IRI.create(IRI_NAME + IRI_DELIMITER + GIVEN_CONTRAINDICATION);
        OWLClass givenContrClass = factory.getOWLClass(givenContrClassIRI);
        IRI inferredContrClassIRI = IRI.create(IRI_NAME + IRI_DELIMITER + INFERRED_CONTRAINDICATION);
        OWLClass inferredContrClass = factory.getOWLClass(inferredContrClassIRI);
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(givenContrClass, topContrClass));
        manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(inferredContrClass, topContrClass));

        // properties
        IRI hasDrugIRI = IRI.create(IRI_NAME + IRI_DELIMITER + "drug");
        OWLObjectProperty hasDrugProp = factory.getOWLObjectProperty(hasDrugIRI);
        IRI hasConditionIRI = IRI.create(IRI_NAME + IRI_DELIMITER + "condition");
        OWLObjectProperty hasConditionProp = factory.getOWLObjectProperty(hasConditionIRI);
        IRI hasSeverityIRI = IRI.create(IRI_NAME + IRI_DELIMITER + "severity");
        OWLDataProperty hasSeverityProp = factory.getOWLDataProperty(hasSeverityIRI);
        IRI hasNDCIRI = IRI.create(IRI_NAME + IRI_DELIMITER + "NDC");
        OWLDataProperty hasNDCProp = factory.getOWLDataProperty(hasNDCIRI);

        Set<OWLClass> cls1 = new HashSet<>();
        Set<OWLClass> cls2 = new HashSet<>();
        Map<OWLNamedIndividual, OWLClass> contrCondMap = new HashMap<>();
        for (String drug : drug2cond.keySet()) {

            // drug
            IRI drugIndIRI = IRI.create(IRI_NAME + IRI_DELIMITER + drug);
            OWLNamedIndividual drugInd = factory.getOWLNamedIndividual(drugIndIRI);
            manager.addAxiom(ontology, factory.getOWLClassAssertionAxiom(topDrugClass, drugInd));

            // drug code
            manager.addAxiom(ontology,
                    factory.getOWLDataPropertyAssertionAxiom(hasNDCProp, drugInd, drug2code.get(drug)));

            // categories
            if (drug2cat.containsKey(drug)) {
                IRI drugCategoryIRI = IRI.create(IRI_NAME + IRI_DELIMITER + drug2cat.get(drug));
                OWLClass drugCategoryClass = factory.getOWLClass(drugCategoryIRI);
                manager.addAxiom(ontology, factory.getOWLSubClassOfAxiom(drugCategoryClass, topDrugClass));
                manager.addAxiom(ontology, factory.getOWLClassAssertionAxiom(drugCategoryClass, drugInd));
            }

            // conditions
            Set<String> condIDs = drug2cond.get(drug);
            for (String condID : condIDs) {
                OWLClass condClass = findICD9Class(condID);
                if (condClass == null) {
                    continue;
                }
                cls1.add(condClass);
                Set<OWLClass> condClasses = new HashSet<>(
                        reasoner.getSubClasses(condClass, false).getFlattened()
                );
                condClasses.add(condClass);

                for (OWLClass subCl : condClasses) {
                    cls2.add(subCl);
                    IRI condIndIRI = IRI.create(IRI_NAME + IRI_DELIMITER +
                            subCl.getIRI().getShortForm());
                    OWLNamedIndividual condInd = factory.getOWLNamedIndividual(condIndIRI);

                    // axioms
                    manager.addAxiom(ontology, factory.getOWLClassAssertionAxiom(subCl, condInd));

                    IRI contrIRI = IRI.create(IRI_NAME + IRI_DELIMITER +
                            subCl.getIRI().getShortForm() + ENTITY_DELIMITER + drug2code.get(drug));
                    OWLNamedIndividual contrInd = factory.getOWLNamedIndividual(contrIRI);

                    manager.addAxiom(ontology, factory.getOWLObjectPropertyAssertionAxiom(
                            hasDrugProp, contrInd, drugInd));
                    manager.addAxiom(ontology, factory.getOWLObjectPropertyAssertionAxiom(
                            hasConditionProp, contrInd, condInd));

                    manager.addAxiom(ontology, factory.getOWLDataPropertyAssertionAxiom(
                            hasSeverityProp, contrInd, severityStr));

                    contrCondMap.put(contrInd, subCl);
                }
            }
        }

        for (OWLNamedIndividual contrInd : contrCondMap.keySet()) {
            OWLClass cl = contrCondMap.get(contrInd);
            if (cls1.contains(cl)) {
                manager.addAxiom(ontology, factory.getOWLClassAssertionAxiom(givenContrClass, contrInd));
            } else {
                manager.addAxiom(ontology, factory.getOWLClassAssertionAxiom(inferredContrClass, contrInd));
            }
        }

        Out.p((cls2.size() - cls1.size()) + " out of " + cls2.size()
                + "  conditions are inferred to have contraindications");

        // save the ontology
        if (!ontFile.exists()) {
            ontFile.createNewFile();
        }

        FileOutputStream outputStream = new FileOutputStream(ontFile);
        BufferedOutputStream buffOutputStream = new BufferedOutputStream(outputStream);
        manager.saveOntology(ontology, buffOutputStream);
    }


    private OWLClass findICD9Class(String code) {
        return icd9Map.get(code);
    }


    public static void main(String args[])
            throws Exception {
        DrugContraindicationMapper converter = new DrugContraindicationMapper(
                new File(args[0]), new File(args[1]), new File(args[2]));
        converter.addICD9Ontology(new File(args[3]));
        converter.computeClassHierarchy();
        converter.createOntology(new File(args[4]));
    }


}
