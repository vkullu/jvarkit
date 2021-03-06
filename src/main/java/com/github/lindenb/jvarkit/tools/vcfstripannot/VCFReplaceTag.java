/*
The MIT License (MIT)

Copyright (c) 2014 Pierre Lindenbaum

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.


History:
* 2014 creation

*/
package com.github.lindenb.jvarkit.tools.vcfstripannot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import htsjdk.variant.variantcontext.Genotype;
import htsjdk.variant.variantcontext.GenotypeBuilder;
import htsjdk.variant.variantcontext.VariantContext;
import htsjdk.variant.variantcontext.VariantContextBuilder;
import htsjdk.variant.variantcontext.writer.VariantContextWriter;
import htsjdk.variant.vcf.VCFFilterHeaderLine;
import htsjdk.variant.vcf.VCFFormatHeaderLine;
import htsjdk.variant.vcf.VCFHeader;
import htsjdk.variant.vcf.VCFHeaderLine;
import htsjdk.variant.vcf.VCFInfoHeaderLine;

import com.github.lindenb.jvarkit.util.picard.SAMSequenceDictionaryProgress;
import com.github.lindenb.jvarkit.util.vcf.VCFUtils;
import com.github.lindenb.jvarkit.util.vcf.VcfIterator;


public class VCFReplaceTag extends AbstractVCFReplaceTag
	{
	private static final org.slf4j.Logger LOG = com.github.lindenb.jvarkit.util.log.Logging.getLog(AbstractVCFReplaceTag.class);
	private Map<String,String> transformMap=new HashMap<>();
	private int replaceTypeNo=-1;
	
	public VCFReplaceTag()
		{
		}
	
	@Override
	protected Collection<Throwable> doVcfToVcf(
			final String inputName,
			final VcfIterator r,
			final VariantContextWriter w)
			throws IOException {

			final VCFHeader header=r.getHeader();
			
			final HashSet<VCFHeaderLine> copyMeta= new HashSet<>(header.getMetaDataInInputOrder());
			
			for(final String key:this.transformMap.keySet())
				{
				switch(this.replaceTypeNo)
					{
					case 0://INFO
						{
						VCFInfoHeaderLine info = header.getInfoHeaderLine(key);
						if(info!=null)
							{
							copyMeta.remove(info);
							copyMeta.add(VCFUtils.renameVCFInfoHeaderLine(info, this.transformMap.get(key)));
							}
						break;
						}
					case 1: //FORMAT
						{
						VCFFormatHeaderLine fmt = header.getFormatHeaderLine(key);
						if(fmt!=null)
							{
							copyMeta.remove(fmt);
							copyMeta.add(VCFUtils.renameVCFFormatHeaderLine(fmt, this.transformMap.get(key)));
							}
						break;
						}
					case 2: //FILTER
						{
						VCFFilterHeaderLine filter = header.getFilterHeaderLine(key);
						if(filter!=null)
							{
							copyMeta.remove(filter);
							copyMeta.add(VCFUtils.renameVCFFilterHeaderLine(filter, this.transformMap.get(key)));
							}
						break;
						}
					default: throw new IllegalStateException(""+this.replaceTypeNo);
					}
				}
			addMetaData(copyMeta);

			final VCFHeader h2=new VCFHeader(copyMeta,header.getSampleNamesInOrder());
			final SAMSequenceDictionaryProgress progress= new SAMSequenceDictionaryProgress(h2);
			w.writeHeader(h2);
			while(r.hasNext())
				{
				VariantContext ctx=progress.watch(r.next());
				VariantContextBuilder b=new VariantContextBuilder(ctx);
				
					switch(this.replaceTypeNo)
						{
						case 0://INFO
							{
							for(String key:this.transformMap.keySet())
								{
								Object o = ctx.getAttribute(key);
								if(o!=null)
									{
									b.rmAttribute(key);
									b.attribute(this.transformMap.get(key), o);
									}
								}
							break;
							}
						case 1: //FORMAT
							{
							List<Genotype> newgenotypes=new ArrayList<>( ctx.getNSamples());
							for(int i=0;i< ctx.getNSamples();++i)
								{
								Genotype g= ctx.getGenotype(i);
								Map<String,Object> atts = g.getExtendedAttributes();
								GenotypeBuilder gb=new GenotypeBuilder(g);
								for(String key:this.transformMap.keySet())
									{
									Object o = atts.get(key);
									if(o!=null)
										{
										atts.remove(key);
										atts.put(this.transformMap.get(key),o);
										}
									}
								gb.attributes(atts);
								newgenotypes.add(gb.make());
								}
							b.genotypes(newgenotypes);
							break;
							}
						case 2: //FILTER
							{
							Set<String> filters=new HashSet<>(ctx.getFilters());
							for(String key:this.transformMap.keySet())
								{
								if(filters.contains(key))
									{
									filters.remove(key);
									filters.add(this.transformMap.get(key));
									}
								}
							b.filters(filters);
							break;
							}
						default: throw new IllegalStateException(""+this.replaceTypeNo);
						}
					
				w.add(b.make());
				if(w.checkError()) break;
				}	
			progress.finish();
			LOG.info("done");
			return RETURN_OK;
			}
	
	@Override
	public Collection<Throwable> initializeKnime()
		{	
		if(super.replaceType==null)
			{
			return wrapException("Undefined replaceType");
			}
		super.replaceType= this.replaceType.toUpperCase();
		if(super.replaceType.equals("FILTER")){replaceTypeNo=2;}
		else if(super.replaceType.equals("INFO")){replaceTypeNo=0;}
		else if(super.replaceType.equals("FORMAT")){replaceTypeNo=1;}
		else
			{	
			return wrapException("Undefined replace type :"+this.replaceType);
			}
		for(final String s:super.userTagList)
			{
			int slash=s.indexOf('/');
			if(slash==-1)
				{
				return wrapException("missing '/' in "+s);
				}
			if(slash==0 || slash+1==s.length())
				{
				return wrapException("bad '/' in "+s);
				}
			final String key = s.substring(0,slash);
			if(replaceTypeNo==1)
				{
				if(key.equals("DP") || key.equals("GT")|| key.equals("PL")||
					key.equals("AD")|| key.equals("GQ"))
					{
					return wrapException("Cannot replace built-in FORMAT fields "+key);
					}
				}
			this.transformMap.put(key,s.substring(slash+1));
			}
		return super.initializeKnime();
		}
	
	@Override
	protected Collection<Throwable> call(String inputName) throws Exception {
		return doVcfToVcf(inputName);
		}

	
	public static void main(String[] args) throws IOException
		{
		new VCFReplaceTag().instanceMainWithExit(args);
		}
	}
