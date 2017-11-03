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

import java.util.Map;
import java.util.HashMap;

import org.apache.log4j.Logger;

import org.ncgr.intermine.PublicationTools;
import org.ncgr.pubmed.PubMedSummary;

import org.intermine.dataconversion.ItemWriter;
import org.intermine.metadata.Model;
import org.intermine.xml.full.Item;

/**
 * Store genetic marker genomic positions, type and motif from a tab-delimited file.
 *
 * primaryIdentifier secondaryIdentifier Type Chromosome Start End Motif
 *
 * @author Sam Hokin, NCGR
 */
public class MarkerChromosomeFileConverter extends BioFileConverter {
	
    private static final Logger LOG = Logger.getLogger(MarkerChromosomeFileConverter.class);

    // store chromosomes and supercontigs in map for repeated use
    Map<String,Item> chromosomeMap = new HashMap<String,Item>();

    /**
     * Create a new MarkerChromosomeFileConverter
     * @param writer the ItemWriter to write out new items
     * @param model the data model
     */
    public MarkerChromosomeFileConverter(ItemWriter writer, Model model) {
        super(writer, model);
    }

    /**
     * {@inheritDoc}
     * Process the marker-chromosome relationships by reading in from a tab-delimited file.
     */
    @Override
    public void process(Reader reader) throws Exception {

        // don't process README files
        if (getCurrentFile().getName().equals("README")) return;

        LOG.info("Processing file "+getCurrentFile().getName()+"...");

        BufferedReader markerReader = new BufferedReader(reader);
	String line;
        while ((line=markerReader.readLine())!=null) {
            if (!line.startsWith("#")) {

                MarkerChromosomeRecord rec = new MarkerChromosomeRecord(line);

                // create the marker
                Item marker = createItem("GeneticMarker");
                marker.setAttribute("primaryIdentifier", rec.primaryIdentifier);
                if (rec.secondaryIdentifier.length()>0) marker.setAttribute("secondaryIdentifier", rec.secondaryIdentifier);
                if (rec.type.length()>0) marker.setAttribute("type", rec.type);
                if (rec.motif.length()>0) marker.setAttribute("motif", rec.motif);
                // set the chromosome or supercontig reference
                // HACK: assume supercontig has "scaffold" or "contig" in the name
                boolean isSupercontig = (rec.chromosome.toLowerCase().contains("scaffold") || rec.chromosome.toLowerCase().contains("contig"));
                Item chromosome = null;
                if (chromosomeMap.containsKey(rec.chromosome)) {
                    chromosome = chromosomeMap.get(rec.chromosome);
                } else {
                    // create and store this chromosome/supercontig
                    if (isSupercontig) {
                        chromosome = createItem("Supercontig");
                    } else {
                        chromosome = createItem("Chromosome");
                    }
                    chromosome.setAttribute("primaryIdentifier", rec.chromosome);
                    store(chromosome);
                    chromosomeMap.put(rec.chromosome, chromosome);
                }
                if (isSupercontig) {
                    marker.setReference("supercontig", chromosome);
                } else {
                    marker.setReference("chromosome", chromosome);
                }

                // create and store the Location object
                Item location = createItem("Location");
                location.setReference("locatedOn", chromosome);
                location.setReference("feature", marker);
                location.setAttribute("start", String.valueOf(rec.start));
                location.setAttribute("end", String.valueOf(rec.end));
                location.setAttribute("strand", String.valueOf(+1));
                store(location);

                // set the chromosomeLocation/supercontigLocation reference and store the marker
                if (isSupercontig) {
                    marker.setReference("supercontigLocation", location);
                } else {
                    marker.setReference("chromosomeLocation", location);
                }                    
                store(marker);

            }
        }
        
        markerReader.close();

    }

    /**
     * Do nothing, all storage is above.
     */
    @Override
    public void close() throws Exception {
    }
    
}