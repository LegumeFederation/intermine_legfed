package org.intermine.bio.dataconversion;

/*
 * Copyright (C) 2015-2016 NCGR
 *
 * This code may be freely distributed and modified under the
 * terms of the GNU Lesser General Public Licence.  This should
 * be distributed with the code.  See the LICENSE file for more
 * information or http://www.gnu.org/copyleft/lesser.html.
 *
 */

import java.io.BufferedReader;
import java.io.Reader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import org.intermine.bio.io.gff3.GFF3Record;
import org.intermine.dataconversion.ItemWriter;
import org.intermine.metadata.Model;
import org.intermine.xml.full.Item;

/**
 * Read synteny blocks for two organisms from a DAGchainer synteny GFF file and store them as SyntenyBlock items,
 * each related to a source and target SyntenicRegion. This is designed to use the GFF annotation produced by DAGchainer.
 *
 * The source and target organism taxIDs are taken from the DAGchainer GFF file name.
 * For example, if source=Phaseolus vulgaris and target=Glycine max, then the file will be, e.g. synteny.3885_with_3847.gff3, or
 * any other file name which contains ".3885_chars_3847.gff" and no other dots or underscores.
 *
 * The DAGchainer GFF file lines must look like this:
 * <pre>
 * phavu.Chr01 DAGchainer syntenic_region 125452 912158 2665.5 - . Name=Pv01.Gm14.2.+;Parent=19;ID=20;Target=glyma.Chr14:48062215..48932270;median_Ks=0.3559
 * </pre>
 * That is:
 * o The target sequence must be given by a Target attribute.
 * o The Name attribute must be a unique identifier.
 * o The median_Ks attribute must be spelled that way.
 * o The source ID and target ID must match the primaryIdentifier of chromosomes in the production database.
 * o The record type must be "syntenic_region."
 * o The target strand, + or -, may optionally be given in the last character of the Name attribute, as in this example. Otherwise, strand isn't recorded for the target.
 * The Parent and ID attributes are not used.
 *
 * @author Sam Hokin, NCGR
 */
public class SyntenyGFFConverter extends BioFileConverter {
	
    private static final Logger LOG = Logger.getLogger(SyntenyGFFConverter.class);

    // define these maps globally and then only store them once in the close() method to avoid duplicate store conflicts
    Map<String,Item> organismMap = new HashMap<String,Item>();
    Map<String,Item> chromosomeMap = new HashMap<String,Item>();
        
    /**
     * Create a new SyntenyGFFConverter
     * @param writer the ItemWriter to write out new items
     * @param model the data model
     */
    public SyntenyGFFConverter(ItemWriter writer, Model model) {
        super(writer, model);
    }

    /**
     * {@inheritDoc}
     * We process each GFF file by creating SyntenyBlock and SyntenicRegion items and storing them.
     */
    @Override
    public void process(Reader reader) throws Exception {

        LOG.info("Processing Synteny file "+getCurrentFile().getName()+"...");

        Map<String,GFF3Record> gffMap = new HashMap<String,GFF3Record>();

        // parse the DAGchainer file name for the taxonomy IDs corresponding to the source and target
        String dagChainerFileName = getCurrentFile().getName();
        String[] bothChunks = dagChainerFileName.split("_");
        String[] sourceChunks = bothChunks[0].split("\\.");
        String[] targetChunks = bothChunks[2].split("\\.");
        String sourceTaxonId = sourceChunks[1];
        String targetTaxonId = targetChunks[0];
        LOG.info("source taxon ID="+sourceTaxonId+"; target taxon ID="+targetTaxonId);

        // create and store organism Items
        Item sourceOrganism;
	if (organismMap.containsKey(sourceTaxonId)) {
	    sourceOrganism = organismMap.get(sourceTaxonId);
	} else {
	    sourceOrganism = createItem("Organism");
	    sourceOrganism.setAttribute("taxonId", String.valueOf(sourceTaxonId));
	    organismMap.put(sourceTaxonId, sourceOrganism);
	}
	Item targetOrganism;
	if (organismMap.containsKey(targetTaxonId)) {
	    targetOrganism = organismMap.get(targetTaxonId);
	} else {
	    targetOrganism = createItem("Organism");
	    targetOrganism.setAttribute("taxonId", String.valueOf(targetTaxonId));
	    organismMap.put(targetTaxonId, targetOrganism);
	}
        
        LOG.info("Created sourceOrganism, taxonId="+sourceTaxonId+" and targetOrganism, taxonId="+targetTaxonId);
        
        // -------------------------------------------------------------------------------------------------------
        // Load the GFF data into a map. Add new chromosomes to chromosome map, keyed by primaryIdentifier.
        // -------------------------------------------------------------------------------------------------------

        BufferedReader gffReader = new BufferedReader(reader);
        LOG.info("Reading DAGChainer GFF file...");
        String line = null;
        while ((line=gffReader.readLine()) != null) {
            if (!line.startsWith("#")) {
                GFF3Record gff = new GFF3Record(line);
                if (gff.getType().equals("syntenic_region")) {
                    String sourceChrName = gff.getSequenceID();       // could potentially alter to match the chado values here
                    String targetChrName = getTargetChromosomeName(gff);     // could potentially alter to match the chado values here
                    if (targetChrName==null) {
                        throw new RuntimeException("GFF record is missing Target= attribute:"+line);
                    }
                    if (sourceChrName!=null && !chromosomeMap.containsKey(sourceChrName)) {
                        // create the chromosome Item and add to the chromosome map
                        Item chromosome = createItem("Chromosome");
                        chromosome.setAttribute("primaryIdentifier", sourceChrName);
                        chromosomeMap.put(sourceChrName, chromosome);
                        LOG.info("Created new source chromosome:"+sourceChrName);
                    }
                    if (targetChrName!=null && !chromosomeMap.containsKey(targetChrName)) {
                        // create the chromosome Item and add to the chromosome map
                        Item chromosome = createItem("Chromosome");
                        chromosome.setAttribute("primaryIdentifier", targetChrName);
                        chromosomeMap.put(targetChrName, chromosome);
                        LOG.info("Created new target chromosome:"+targetChrName);
                    }
                    // store GFF records in gff map; Name is unique (we hope)
		    gffMap.put(getSyntenyBlockName(gff), gff);
                }
            }
        }
        gffReader.close();
        
        LOG.info("Read "+gffMap.size()+" syntenic_region GFF records.");
        
        // ----------------------------------------------------------------------
        // Now spin through the gffMap records and store the SyntenyBlock Items
        // ----------------------------------------------------------------------

        LOG.info("Creating, linking and storing synteny blocks...");

        for (Map.Entry<String,GFF3Record> entry : gffMap.entrySet()) {
            
            String syntenyBlockID = entry.getKey();
            GFF3Record gff = entry.getValue();
            
            String sourceChrName = gff.getSequenceID();
            String targetChrName = getTargetChromosomeName(gff);

            // populate the source region and its location
            Item sourceChromosome = chromosomeMap.get(sourceChrName);
            Item sourceRegion = createItem("SyntenicRegion");
            BioStoreHook.setSOTerm(this, sourceRegion, "syntenic_region", getSequenceOntologyRefId());
            Item sourceChromosomeLocation = createItem("Location");
            populateSourceRegion(sourceRegion, gff, sourceOrganism, sourceChromosome, sourceChromosomeLocation);
            
            // populate the target region and its location
            Item targetChromosome = chromosomeMap.get(targetChrName);
            Item targetRegion = createItem("SyntenicRegion");
            BioStoreHook.setSOTerm(this, targetRegion, "syntenic_region", getSequenceOntologyRefId());
            Item targetChromosomeLocation = createItem("Location");
            populateTargetRegion(targetRegion, gff, targetOrganism, targetChromosome, targetChromosomeLocation);

            // get the medianKs value for this block
            Map<String, List<String>> attributes = gff.getAttributes();
            String medianKs = attributes.get("median_Ks").get(0);

            // associate the two regions with this synteny block
            Item syntenyBlock = createItem("SyntenyBlock");
            LOG.info("Storing SyntenyBlock:"+syntenyBlockID);
            syntenyBlock.setAttribute("primaryIdentifier", syntenyBlockID);
            syntenyBlock.setAttribute("medianKs", medianKs);
            syntenyBlock.setReference("sourceRegion", sourceRegion);
            syntenyBlock.setReference("targetRegion", targetRegion);
            store(syntenyBlock);

	    sourceRegion.setReference("syntenyBlock", syntenyBlock);
            store(sourceRegion);
            store(sourceChromosomeLocation);

	    targetRegion.setReference("syntenyBlock", syntenyBlock);
            store(targetRegion);
            store(targetChromosomeLocation);
            
        }

    }

    /**
     * {@inheritDoc}
     * Store the organisms and chromosomes that were created in processing all the GFF files.
     */
    @Override
    public void close() throws Exception {

        LOG.info("Storing "+organismMap.size()+" organism items...");
	for (Item organism : organismMap.values()) store(organism);

	LOG.info("Storing "+chromosomeMap.size()+" chromosome items...");
	for (Item chromosome : chromosomeMap.values()) store(chromosome);

    }

    /**
     * Populate the attributes of a SequenceFeature Item with a GFF3Record's data; Organism, Chromosome and ChromosomeLocation Items must be passed in as well.
     * @param sequenceFeature the SequenceFeature to be populated
     * @param gff the GFF3Record holding the data
     * @param organism the Organism Item
     * @param chromosome the Chromosome Item
     * @param chromosomeLocation the Location Item
     */
    void populateSequenceFeature(Item sequenceFeature, GFF3Record gff, Item organism, Item chromosome, Item chromosomeLocation) {
        sequenceFeature.setAttribute("primaryIdentifier", getSyntenyBlockName(gff));
        sequenceFeature.setAttribute("length", String.valueOf(gff.getEnd()-gff.getStart()+1));
        sequenceFeature.setAttribute("score", String.valueOf(gff.getScore()));
        sequenceFeature.setReference("organism", organism);
        sequenceFeature.setReference("chromosome", chromosome);
        sequenceFeature.setReference("chromosomeLocation", chromosomeLocation);
        chromosomeLocation.setAttribute("start", String.valueOf(gff.getStart()));
        chromosomeLocation.setAttribute("end", String.valueOf(gff.getEnd()));
        chromosomeLocation.setAttribute("strand", String.valueOf(gff.getStrand()));
        chromosomeLocation.setReference("feature", sequenceFeature);
        chromosomeLocation.setReference("locatedOn", chromosome);
    }

    
    /**
     * Populate the attributes of a source SyntenicRegion with a GFF3Record's data. All this does is append ".source" to the primaryIdentifier.
     *
     * @param syntenicRegion the SyntenicRegion Item
     * @param gff the GFF3Record holding the data
     * @param chromosome the source Chromosome Item
     * @param chromosomeLocation the source Location Item to be filled in
     */
    void populateSourceRegion(Item syntenicRegion, GFF3Record gff, Item organism, Item chromosome, Item chromosomeLocation) {
        populateSequenceFeature(syntenicRegion, gff, organism, chromosome, chromosomeLocation);
        String primaryIdentifier = getSyntenyBlockName(gff)+".source"; // hopefully unique!
        syntenicRegion.setAttribute("primaryIdentifier", primaryIdentifier);
    }

    /**
     * Populate the attributes of a target SyntenicRegion with a GFF3Record's DAGchainer attributes data; Organism, Chromosome and ChromosomeLocation Items must be passed in as well.
     *
     * @param syntenicRegion the SyntenicRegion Item
     * @param gff the GFF3Record holding the data
     * @param chromosome the target Chromosome Item
     * @param chromosomeLocation the target Location Item to be filled in
     */
    void populateTargetRegion(Item syntenicRegion, GFF3Record gff, Item organism, Item chromosome, Item chromosomeLocation) {
        // first populate the syntenicRegion before we override a lot of the values from the DAGchainer attributes
        populateSequenceFeature(syntenicRegion, gff, organism, chromosome, chromosomeLocation);
        // set primaryIdentifier, hopefully unique, and length
        String primaryIdentifier = getSyntenyBlockName(gff)+".target";
        syntenicRegion.setAttribute("primaryIdentifier", primaryIdentifier);
        // set the target length
        syntenicRegion.setAttribute("length", String.valueOf(getTargetEnd(gff)-getTargetStart(gff)+1));
        // override chromosome location with DAGchainer stuff
        chromosomeLocation.setAttribute("start", String.valueOf(getTargetStart(gff)));
        chromosomeLocation.setAttribute("end", String.valueOf(getTargetEnd(gff)));
        if (getTargetStrand(gff)!='\0') chromosomeLocation.setAttribute("strand", String.valueOf(getTargetStrand(gff)));
    }

    /**
     * Return the DAGchainer target strand from a DAGchainer Name attribute, checking for ' ' instead of '+' since GFF3Record converts a plus to space.
     */
    char getTargetStrand(GFF3Record gff) {
	String name = gff.getNames().get(0);
        char endChar = name.charAt(name.length()-1);
        if (endChar==' ' || endChar=='+') {
	    return '+';
	} else if (endChar=='-') {
            return '-';
        } else {
            return '\0';
        }
    }

    /**
     * Return the DAGchainer target chromosome from a DAGchainer GFF3Record
     */
    String getTargetChromosomeName(GFF3Record gff) {
        if (gff.getTarget()==null) {
            return null;
        } else {
            String[] chunks = gff.getTarget().split(":");
            return chunks[0];
        }
    }
    
    /**
     * Return the target sequence start from a DAGchainer GFF3Record
     */
    int getTargetStart(GFF3Record gff) {
        String[] chunks = gff.getTarget().split(":");
        String range = chunks[1];
        String[] pieces = range.split("\\.\\.");
        return Integer.parseInt(pieces[0]);
    }

    /**
     * Return the target sequence end from a DAGchainer GFF3Record
     */
    int getTargetEnd(GFF3Record gff) {
        String[] chunks = gff.getTarget().split(":");
        String range = chunks[1];
        String[] pieces = range.split("\\.\\.");
        return Integer.parseInt(pieces[1]);
    }

    /**
     * Return a syteny block identifier by replacing a space at the end with +, since GFF3Record.getNames() treats + as a space. Names shouldn't have any spaces anywhere else.
     */
    String getSyntenyBlockName(GFF3Record gff) {
	String name = gff.getNames().get(0);
	if (name.endsWith(" ")) name = name.replace(" ", "+");
	return name;
    }

}
